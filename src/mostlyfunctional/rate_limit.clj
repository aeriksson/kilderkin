(ns mostlyfunctional.rate-limit
  "TODO"
  (:require [clojure.data.priority-map :refer [priority-map-by]]))

;;;; Utils ;;;;

(defn ^:private sliding-cmp
  "Comparator used to sort items by expiry time."
  [[win-a] [win-b]]
  (compare (last win-a) (last win-b)))

(defn ^:private tumbling-cmp
  "Comparator used to sort items by expiry time."
  [[win-a] [win-b]]
  (compare win-a win-b))

(defn ^:private hopping-cmp
  "Comparator used to sort items by expiry time."
  [[win-a] [win-b]]
  (compare (first (last win-a)) (first (last win-b))))

(defn ^:private sliding-ts>
  "???"
  [ts [_ [tss]]]
  (< (last tss) ts))

(defn ^:private tumbling-ts>
  "???"
  [ts [_ [bucket-ts]]]
  (< bucket-ts ts))

(defn ^:private hopping-ts>
  "???"
  [ts [_ [buckets]]]
  (< (first (last buckets)) ts))

(def ^:private empty-sliding-window-pm
  "'Root' priority map used when building sliding window rate limiters."
  (priority-map-by sliding-cmp))

(def ^:private empty-tumbling-window-pm
  "'Root' priority map used when building tumbling window rate limiters."
  (priority-map-by tumbling-cmp))

(def ^:private empty-hopping-window-pm
  "'Root' priority map used when building hopping window rate limiters."
  (priority-map-by hopping-cmp))

(defn ^:private round-down
  "Rounds `n` down to the nearest multiple of `base`."
  [n base]
  (* base (quot n base)))

;;;; API ;;;;

(defprotocol ReducingRateLimiter
  (get-key-count [this k])
  (get-key-ts [this k])
  (get-key-val [this k])
  (update-key [this ts k v])
  (take-expired [this ts])
  (drop-expired [this ts]))

(defrecord RateLimiter
  [priority-map window-type window-ms hop-ms max-per-window reduce-fn init-val])

(defn ^:private take-expired-helper
  [priority-map expired?]
  (map second (take-while expired? priority-map)))

(defn ^:private drop-expired-helper
  [this expired?]
  (update this
          :priority-map
          #(->> % (take-while expired?) (map first) (apply disj %))))

(defrecord SlidingWindowRateLimiter
  [priority-map window-ms max-per-window reduce-fn init-val]
  ReducingRateLimiter
  (get-key-count [_ k]
    (some-> priority-map k first count))
  (get-key-ts [_ k]
    (some-> priority-map k first last))
  (get-key-val [_ k]
    (some-> priority-map k second))
  (update-key [this ts k v]
    (update-in this [priority-map k]
               (fn [[win]]
                 (if win
                   (let [ts' (- ts window-ms)]
                     (conj (apply disj win (take-while #(< % ts') win)) ts))
                   (sorted-set ts)))))
  (take-expired [_ ts]
    (take-expired-helper priority-map #(sliding-ts> ts %)))
  (drop-expired [this ts]
    (drop-expired-helper this #(sliding-ts> ts %))))

(defrecord TumblingWindowRateLimiter
  [priority-map window-ms max-per-window reduce-fn init-val]
  ReducingRateLimiter
  (get-key-count [_ k]
    (some-> priority-map k ffirst))
  (get-key-ts [_ k]
    (some-> priority-map k nfirst))
  (get-key-val [_ k]
    (some-> priority-map k second))
  (update-key [this ts k v]
    (update-in this [priority-map k]
               (fn [[[bucket-ts n]]]
                 (if (and bucket-ts (>= bucket-ts (- ts window-ms)))
                   [bucket-ts (inc n)]
                   [(round-down ts window-ms) 1]))))
  (take-expired [_ ts]
    (take-expired-helper priority-map #(tumbling-ts> ts %)))
  (drop-expired [this ts]
    (drop-expired-helper this #(tumbling-ts> ts %))))

(defrecord HoppingWindowRateLimiter
  [priority-map window-ms hop-ms max-per-window reduce-fn init-val]
  ReducingRateLimiter
  (get-key-count [_ k]
    (some->> priority-map k (map second) (apply +)))
  (get-key-ts [_ k]
    (some-> priority-map k last first))
  (get-key-val [_ k]
    (some-> priority-map k second))
  (update-key [this ts k v]
    (update-in this [priority-map k]
               (fn [[win]]
                 (let [ts'       (- ts window-ms)
                       buckets   (->> win
                                      keys
                                      (take-while #(< % ts'))
                                      (apply dissoc win))
                       bucket-ts (round-down ts hop-ms)]
                   (if buckets
                     (if (= (first (last buckets)) bucket-ts)
                       (update buckets bucket-ts inc)
                       (assoc buckets bucket-ts 1))
                     (sorted-map bucket-ts 1))))))
  (take-expired [_ ts]
    (take-expired-helper priority-map #(hopping-ts> ts %)))
  (drop-expired [this ts]
    (drop-expired-helper this #(hopping-ts> ts %))))

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
   (->RateLimiter
    empty-sliding-window-pm :sliding window-ms 0 max-per-window reduce-fn init-val)))

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
   (->RateLimiter
    empty-tumbling-window-pm :tumbling window-ms 0 max-per-window reduce-fn init-val)))

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
   (->RateLimiter
    empty-hopping-window-pm :hopping window-ms hop-ms max-per-window reduce-fn init-val)))

(defn truncate-ts
  "Removes and returns all cached values in the "
  [rl ts]
  (let [lookback (- ts (:window-ms rl))
        expired? (case (:window-type rl)
                   :sliding  #(<= (last %) lookback)
                   :tumbling #(<= (first %) lookback)
                   :hopping  #(<= (first (last %)) lookback))]
    (loop [xs [] pm (:priority-map rl)]
      (let [[k [win v]] (peek pm)]
        (if (some-> win expired?)
          (recur (conj xs [k v]) (pop pm))
          [(assoc rl :priority-map pm) xs])))))

(defn update-window
  [rl win ts]
  (let [lookback (- ts (:window-ms rl))]
    (case (:window-type rl)
      :sliding  (if win
                  (conj (apply disj win (take-while #(< % lookback) win)) ts)
                  (sorted-set ts))
      :tumbling (let [[bucket-ts n] win]
                  (if (and bucket-ts (>= bucket-ts lookback))
                    [bucket-ts (inc n)]
                    [(round-down ts (:window-ms rl)) 1]))
      :hopping  (let [buckets   (->> win
                                     keys
                                     (take-while #(< % lookback))
                                     (apply dissoc win))
                      bucket-ts (round-down ts (:hop-ms rl))]
                  (if buckets
                    (if (= (first (last buckets)) bucket-ts)
                      (update buckets bucket-ts inc)
                      (assoc buckets bucket-ts 1))
                    (sorted-map bucket-ts 1))))))

(defn allow-window?
  [rl win]
  (case (:window-type rl)
    :sliding  (<= (count win) (:max-per-window rl))
    :tumbling (<= (second win) (:max-per-window rl))
    :hopping  (<= (apply + (map second win)) (:max-per-window rl))))

(defn allow-ts?
  "Checks if the given key should be rate-limited."
  ([rl ts k]
   (allow-ts? rl ts k nil))
  ([rl ts k v]
   (let [[win acc] (some-> rl :priority-map (get k))
         win'      (update-window rl win ts)
         allow?    (allow-window? rl win')]
     (if allow?
       (let [rl' (update rl :priority-map conj [k [win']])]
         (if acc
           [rl' true acc]
           [rl' true]))
       (if-let [f (:reduce-fn rl)]
         [(update-in rl [:priority-map k 1] #(f (or % (:init-val rl)) v)) false]
         [(assoc-in rl [:priority-map k 1] v) false])))))

;; (defn take-expired
;;   "Returns all elements in the provided rate limiter that have expired."
;;   [rl ts]
;;   (map second (take-while #(< (first %) ts) (:priority-map rl))))
;;
;; (defn drop-expired
;;   "Drops all elements in the provided rate limiter that have expired, and
;;   returns the resulting rate limiter."
;;   [rl ts]
;;   (->> (:priority-map rl)
;;        (take-while (fn [[k _]] (< k ts)))
;;        (map first)
;;        (apply disj (:priority-map rl))
;;        (assoc rl :priority-map)))
;;
;; (defn conj-elem
;;   [rl [ts k-or-kv]]
;;   (let [[k v] (if (vector? k-or-kv) k-or-kv [k-or-kv])]
;;     (ins rl ts k v)))
;;
;; (defn allow-elem?
;;   ([rl ts k]
;;    (allow-elem? rl ts k nil))
;;   ([rl ts k v]
;;    (allowed? )))

; ; truncate:  RateLimiter[K,V] -> [V]
; ; truncated: RateLimiter[K,V] -> RateLimiter[K,V]
; ; conj:      RateLimiter[K,V] -> K -> RateLimiter[K,V]
; allow?:    RateLimiter[K,V] -> K -> Bool
; get:       RateLimiter[K,V] -> K -> V
; empty:     RateLimiter[K,V] -> RateLimiter[K,V]
; count:     RateLimiter[K,V] -> RateLimiter[K,V]
