(ns mostlyfunctional.rate-limit
  "TODO"
  (:require [clojure.data.priority-map :refer [priority-map-keyfn]]))

;;;; Utils ;;;;

(defn ^:private sliding-keyfn
  [[tss]]
  (last tss))

(defn ^:private tumbling-keyfn
  [[[ts]]]
  ts)

(defn ^:private hopping-keyfn
  [[buckets]]
  (first (last buckets)))

(defn ^:private sliding-ts>=
  [ts [_ v]]
  (>= ts (sliding-keyfn v)))

(defn ^:private tumbling-ts>=
  [ts [_ v]]
  (>= ts (tumbling-keyfn v)))

(defn ^:private hopping-ts>=
  [ts [_ v]]
  (>= ts (hopping-keyfn v)))

(def ^:private empty-sliding-pm
  "'Root' priority map used when building sliding window rate limiters."
  (priority-map-keyfn sliding-keyfn))

(def ^:private empty-tumbling-pm
  "'Root' priority map used when building tumbling window rate limiters."
  (priority-map-keyfn tumbling-keyfn))

(def ^:private empty-hopping-pm
  "'Root' priority map used when building hopping window rate limiters."
  (priority-map-keyfn hopping-keyfn))

(defn ^:private round-down
  "Rounds `n` down to the nearest multiple of `base`."
  [n base]
  (* base (quot n base)))

;;;; API ;;;;

(defprotocol ReducingRateLimiter
  (allow-key? [this ts k])
  (set-cached [this k v])
  (get-cached [this k])
  (update-key [this ts k])
  (take-expired [this ts])
  (drop-expired [this ts]))

(defrecord RateLimiter
  [priority-map window-type window-ms hop-ms max-per-window reduce-fn init-val])

(defn ^:private take-expired-helper
  [priority-map expired?]
  (map (fn [[k [_ v]]] [k v]) (take-while expired? priority-map)))

(defn ^:private drop-expired-helper
  [this expired?]
  (update this
          :priority-map
          #(->> % (take-while expired?) (map first) (apply dissoc %))))

(defrecord SlidingWindowRateLimiter
  [priority-map window-ms max-per-window reduce-fn init-val]
  ReducingRateLimiter
  (get-cached [_ k]
    (get-in priority-map [k 1]))
  (set-cached [this k v]
    (if reduce-fn
      (update-in this [:priority-map k 1] #(reduce-fn (or % init-val) v))
      (assoc-in this [:priority-map k 1] v)))
  (allow-key? [this ts k]
    (let [[tss] (get priority-map k)]
      (if tss
        (let [ts' (- ts window-ms)]
          (< (count (drop-while #(<= % ts') tss)) max-per-window))
        true)))
  (update-key [this ts k]
    (update-in this [:priority-map k]
               (fn [[win]]
                 (if win
                   (let [ts' (- ts window-ms)]
                     [(conj (apply disj win (take-while #(< % ts') win)) ts)])
                   ;; TODO: this actually can't be a set because duplicates won't be counted
                   [(sorted-set ts)]))))
  (take-expired [_ ts]
    (let [ts' (- ts window-ms)]
      (take-expired-helper priority-map #(sliding-ts>= ts' %))))
  (drop-expired [this ts]
    (let [ts' (- ts window-ms)]
      (drop-expired-helper this #(sliding-ts>= ts' %)))))

(defrecord TumblingWindowRateLimiter
  [priority-map window-ms max-per-window reduce-fn init-val]
  ReducingRateLimiter
  (get-cached [_ k]
    (some-> priority-map (get k) second))
  (set-cached [this k v]
    (if reduce-fn
      (update-in this [:priority-map k 1] #(reduce-fn (or % init-val) v))
      (assoc-in this [:priority-map k 1] v)))
  (allow-key? [this ts k]
    (let [[[bucket-ts n]] (get priority-map k)]
      (if bucket-ts
        (or (<= bucket-ts (- ts window-ms)) (< n max-per-window))
        true)))
  (update-key [this ts k]
    (update-in this [:priority-map k]
               (fn [[[bucket-ts n]]]
                 (if (and bucket-ts (> bucket-ts (- ts window-ms)))
                   [[bucket-ts (inc n)]]
                   [[(round-down ts window-ms) 1]]))))
  (take-expired [_ ts]
    (let [ts' (- ts window-ms)]
      (take-expired-helper priority-map #(tumbling-ts>= ts' %))))
  (drop-expired [this ts]
    (let [ts' (- ts window-ms)]
      (drop-expired-helper this #(tumbling-ts>= ts' %)))))

(defrecord HoppingWindowRateLimiter
  [priority-map window-ms hop-ms max-per-window reduce-fn init-val]
  ReducingRateLimiter
  (get-cached [_ k]
    (some-> priority-map (get k) second))
  (set-cached [this k v]
    (if reduce-fn
      (update-in this [:priority-map k 1] #(reduce-fn (or % init-val) v))
      (assoc-in this [:priority-map k 1] v)))
  (allow-key? [this ts k]
    (let [[buckets] (get priority-map k)]
      (if buckets
        (let [ts' (- ts window-ms)]
          (< (count (drop-while #(<= (first %) ts') buckets)) max-per-window))
        true)))
  (update-key [this ts k]
    (update-in this [:priority-map k]
               (fn [[win]]
                 (let [threshold (- ts window-ms)
                       buckets   (->> win
                                      keys
                                      (take-while #(<= % threshold))
                                      (apply dissoc win))
                       bucket-ts (round-down ts hop-ms)]
                   (if buckets
                     (if (= (first (last buckets)) bucket-ts)
                       [(update buckets bucket-ts inc)]
                       [(assoc buckets bucket-ts 1)])
                     [(sorted-map bucket-ts 1)])))))
  (take-expired [_ ts]
    (let [ts' (- ts window-ms)]
      (take-expired-helper priority-map #(hopping-ts>= ts' %))))
  (drop-expired [this ts]
    (let [ts' (- ts window-ms)]
      (drop-expired-helper this #(hopping-ts>= ts' %)))))

(defn sliding-window-rate-limiter
  "Creates a sliding window rate-limiter with the given window size (and
  optionally reduce function and initial value, for merging rate-limited
  values)."
  ([window-ms]
   (sliding-window-rate-limiter window-ms 1 nil nil))
  ([window-ms max-per-window]
   (sliding-window-rate-limiter window-ms max-per-window nil nil))
  ([window-ms max-per-window reduce-fn]
   (sliding-window-rate-limiter window-ms max-per-window reduce-fn nil))
  ([window-ms max-per-window reduce-fn init-val]
   (->SlidingWindowRateLimiter
    empty-sliding-pm window-ms max-per-window reduce-fn init-val)))

(defn tumbling-window-rate-limiter
  "Creates a tumbling window rate-limiter with the given window size (and
  optionally reduce function and initial value, for merging rate-limited
  values)."
  ([window-ms]
   (tumbling-window-rate-limiter window-ms 1 nil nil))
  ([window-ms max-per-window]
   (tumbling-window-rate-limiter window-ms max-per-window nil nil))
  ([window-ms max-per-window reduce-fn]
   (tumbling-window-rate-limiter window-ms max-per-window reduce-fn nil))
  ([window-ms max-per-window reduce-fn init-val]
   (->TumblingWindowRateLimiter
    empty-tumbling-pm window-ms max-per-window reduce-fn init-val)))

(defn hopping-window-rate-limiter
  "Creates a hopping window rate-limiter with the given window size and how (and
  optionally reduce function and initial value, for merging rate-limited
  values)."
  ([window-ms hop-ms]
   (hopping-window-rate-limiter window-ms hop-ms 1 nil nil))
  ([window-ms hop-ms max-per-window]
   (hopping-window-rate-limiter window-ms hop-ms max-per-window nil nil))
  ([window-ms hop-ms max-per-window reduce-fn]
   (hopping-window-rate-limiter window-ms hop-ms max-per-window reduce-fn nil))
  ([window-ms hop-ms max-per-window reduce-fn init-val]
   (->HoppingWindowRateLimiter
    empty-hopping-pm window-ms hop-ms max-per-window reduce-fn init-val)))

(defn truncate-ts
  "Removes and returns all cached values in the "
  [rl ts]
  [(drop-expired rl ts) (take-expired rl ts)])

(defn allow-ts?
  "Checks if the given key should be rate-limited."
  ([rl ts k]
   (allow-ts? rl ts k nil))
  ([rl ts k v]
   (if (allow-key? rl ts k)
     [(update-key rl ts k) true (get-cached rl k)]
     [(set-cached rl k v) false])))
