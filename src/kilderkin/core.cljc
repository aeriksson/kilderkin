(ns kilderkin.core
  "Immutable data structures for efficient rate limiting."
  (:require #?(:clj [clojure.data.priority-map :refer [priority-map-keyfn]])
            #?(:cljs [tailrecursion.priority-map :refer [priority-map-keyfn]])))

;;;; Defaults ;;;;

(def default-config
  "The default config values for new rate limiters."
  {:window-type    :sliding
   :window-size    1000
   :max-per-window 1})

;;;; Utils ;;;;

(defn ^:private now []
  #?(:clj  (System/currentTimeMillis))
  #?(:cljs (.getTime (js/Date.))))

(defn ^:private sliding-window-keyfn  [[tss]]     (last tss))
(defn ^:private tumbling-window-keyfn [[[ts]]]    ts)
(defn ^:private hopping-window-keyfn  [[buckets]] (first (last buckets)))

(defn ^:private sliding-ts>=  [ts [_ v]] (>= ts (sliding-window-keyfn v)))
(defn ^:private tumbling-ts>= [ts [_ v]] (>= ts (tumbling-window-keyfn v)))
(defn ^:private hopping-ts>=  [ts [_ v]] (>= ts (hopping-window-keyfn v)))

(defn ^:private round-down
  "Rounds `n` down to the nearest multiple of `base`."
  [n base]
  (- n (mod n base)))

(defn ^:private round-down-with-offset
  "Rounds `n` down to the nearest multiple of x with offset o (i.e. the nearest
  smaller number in the sequence [..., o - base, o, o + base, ...])."
  [n base o]
  (+ (round-down (- n o) base) o))

(defn ^:private get-bucket
  "Maps `ts` to its corresponding bucket of size `bucket-size`, optionally
  offset by a 'random' key-dependent value (hash of `k`) to prevent the buckets
  for all keys rolling at the same time."
  ([ts bucket-size]
   (get-bucket ts bucket-size nil))
  ([ts bucket-size k]
   (if k
     (round-down-with-offset ts bucket-size (mod (hash k) bucket-size))
     (round-down ts bucket-size))))

(defn ^:private take-expired-helper
  [pm exp?]
  (filter second (map (fn [[k [_ v]]] [k v]) (take-while exp? pm))))

(defn ^:private drop-expired-helper
  [rl exp?]
  (update rl :priority-map #(apply dissoc % (map first (take-while exp? %)))))

;;;; Rate Limiters ;;;;

(defprotocol CachingRateLimiter
  "Protocol for rate limiters that can cache data for missed entries."
  (allow?
   [this k ts]
   "Checks whether the key `k` is allowed at time `ts` or should be
   rate-limited.")
  (insert
   [this k ts] [this k ts v]
   "Updates the rate limiter with key `k` at time `ts`. If `v` is provided, it's
   cached if and only if `k` is not allowed.")
  (insert-allow?
   [this k ts] [this k ts v]
   "Updates the rate limiter with key `k` at time `ts`. If `v` is provided, it's
   cached if and only if `k` is not allowed.

   Returns a triple containing:
   1. The modified rate limiter.
   2. A flag saying if the key should be allowed (true) or rate-limited (false).
   3. The previously cached value for `k`, if it exists and is dropped by the
      insert operation (this happens when the previous insert for `k` was
      rate-limited and a value was provided for caching).

   This fn is really just a more convenient/efficient way to run:
   ```
   [(insert this k ts v)
    (allow? this k ts)
    (if (allow? this k ts) (get-cached this k))]
   ```")
  (get-cached
   [this k]
   "Gets the cached value for `k`, if one exists.")
  (take-expired
   [this ts]
   "Returns a sequence of all cached values that would be expired at `ts`, as
   key-value pairs." )
  (drop-expired
   [this ts]
   "Updates the rate limiter such that the state for all keys that are expired
   at `ts` are removed."))

(defrecord SlidingWindowRateLimiter
  [priority-map options]
  CachingRateLimiter
  (allow? [this k ts]
    (if-let [[tss] (get priority-map k)]
      (let [ts' (- ts (:window-ms options))]
        (< (count (drop-while #(<= % ts') tss)) (:max-per-window options)))
      true))
  (insert [this k ts]
    (first (insert-allow? this k ts nil)))
  (insert [this k ts v]
    (first (insert-allow? this k ts v)))
  (insert-allow? [this k ts]
    (insert-allow? this k ts nil))
  (insert-allow? [this k ts v]
    (let [[tss v']  (get priority-map k)
          cutoff    (- ts (:window-ms options))
          expired   (and tss (take-while #(<= % cutoff) tss))
          allowed?  (or (not tss) (< (- (count tss) (count expired))
                                     (:max-per-window options)))
          remaining (and tss (vec (drop (count expired) tss)))
          red-fn    (:reduce-fn options)
          state     (if allowed?
                      (if tss [(sort (conj remaining ts))] [[ts] nil])
                      (if v
                        (if (and red-fn (or v' (contains? options :init-val)))
                          [tss (red-fn (or v' (:init-val options)) v)]
                          [tss v])
                        [tss]))
          this'     (assoc-in this [:priority-map k] state)]
      (if (and allowed? v')
        [this' allowed? v']
        [this' allowed?])))
  (get-cached [_ k]
    (get-in priority-map [k 1]))
  (take-expired [_ ts]
    (let [ts' (- ts (:window-ms options))]
      (take-expired-helper priority-map #(sliding-ts>= ts' %))))
  (drop-expired [this ts]
    (let [ts' (- ts (:window-ms options))]
      (drop-expired-helper this #(sliding-ts>= ts' %)))))

(defrecord TumblingWindowRateLimiter
  [priority-map options]
  CachingRateLimiter
  (allow? [this k ts]
    (if-let [[[b-ts n]] (get priority-map k)]
      (let [stagger? (:stagger-bucket-offsets options)
            b-ts'    (get-bucket ts (:window-ms options) (when stagger? k))]
        (or (not= b-ts b-ts') (< n (:max-per-window options))))
      true))
  (insert [this k ts]
    (first (insert-allow? this k ts nil)))
  (insert [this k ts v]
    (first (insert-allow? this k ts v)))
  (insert-allow? [this k ts]
    (insert-allow? this k ts nil))
  (insert-allow? [this k ts v]
    (let [stagger?       (:stagger-bucket-offsets options)
          b-ts           (get-bucket ts (:window-ms options) (when stagger? k))
          [[b-ts' n] v'] (get priority-map k)
          allowed?       (or (not= b-ts' b-ts) (< n (:max-per-window options)))
          red-fn         (:reduce-fn options)
          state          (if allowed?
                           [[b-ts (if (= b-ts b-ts') (inc n) 1)]]
                           (if v
                             (if (and red-fn
                                      (or v' (contains? options :init-val)))
                               [[b-ts' n] (red-fn
                                           (or v' (:init-val options)) v)]
                               [[b-ts' n] v])
                             [[b-ts' n]]))
          this'          (assoc-in this [:priority-map k] state)]
      (if (and allowed? v')
        [this' allowed? v']
        [this' allowed?])))
  (get-cached [_ k]
    (get-in priority-map [k 1]))
  (take-expired [_ ts]
    (let [ts' (- ts (:window-ms options))]
      (take-expired-helper priority-map #(tumbling-ts>= ts' %))))
  (drop-expired [this ts]
    (let [ts' (- ts (:window-ms options))]
      (drop-expired-helper this #(tumbling-ts>= ts' %)))))

(defrecord HoppingWindowRateLimiter
  [priority-map options]
  CachingRateLimiter
  (allow? [this k ts]
    (if-let [[buckets] (get priority-map k)]
      (let [ts' (- ts (:window-ms options))]
        (< (apply + (map second (drop-while #(<= (first %) ts') buckets)))
           (:max-per-window options)))
      true))
  (insert [this k ts]
    (first (insert-allow? this k ts nil)))
  (insert [this k ts v]
    (first (insert-allow? this k ts v)))
  (insert-allow? [this k ts]
    (insert-allow? this k ts nil))
  (insert-allow? [this k ts v]
    (let [window-ms    (:window-ms options)
          stagger?     (:stagger-bucket-offsets options)
          bucket-ts    (get-bucket ts (:hop-ms options) (when stagger? k))
          cutoff       (- ts window-ms)
          [buckets v'] (get priority-map k)
          expired      (take-while #(<= % cutoff) (map first buckets))
          remaining    (apply dissoc buckets expired)
          allowed?     (or (not buckets) (< (apply + (vals remaining))
                                            (:max-per-window options)))
          reduce-fn    (:reduce-fn options)
          state        (if allowed?
                         (if (contains? remaining bucket-ts)
                           [(update remaining bucket-ts inc)]
                           [(assoc remaining bucket-ts 1)])
                         (if v
                           (if (and reduce-fn
                                    (or v' (contains? options :init-val)))
                             [buckets
                              (reduce-fn (or v' (:init-val options)) v)]
                             [buckets v])
                           [buckets]))
          this'        (assoc-in this [:priority-map k] state)]
      (if (and allowed? v')
        [this' allowed? v']
        [this' allowed?])))
  (get-cached [_ k]
    (get-in priority-map [k 1]))
  (take-expired [_ ts]
    (let [ts' (- ts (:window-ms options))]
      (take-expired-helper priority-map #(hopping-ts>= ts' %))))
  (drop-expired [this ts]
    (let [ts' (- ts (:window-ms options))]
      (drop-expired-helper this #(hopping-ts>= ts' %)))))

;;;; Constructor ;;;;

(defn rate-limiter
  "Builds a rate-limiter from the provided configuration."
  [config]
  (let [config (merge default-config config)]
    (case (:window-type config)
      :sliding  (->SlidingWindowRateLimiter
                 (priority-map-keyfn sliding-window-keyfn)
                 (select-keys config [:window-ms
                                      :max-per-window
                                      :reduce-fn
                                      :init-val]))
      :tumbling (->TumblingWindowRateLimiter
                 (priority-map-keyfn tumbling-window-keyfn)
                 (select-keys config [:window-ms
                                      :max-per-window
                                      :reduce-fn
                                      :init-val
                                      :stagger-bucket-offsets]))
      :hopping  (->HoppingWindowRateLimiter
                 (priority-map-keyfn hopping-window-keyfn)
                 (select-keys config [:window-ms
                                      :hop-ms
                                      :max-per-window
                                      :reduce-fn
                                      :init-val
                                      :stagger-bucket-offsets])))))

;;;; Wall-clock-time API ;;;;

(defn allow-now?
  "Checks whether the key `k` is allowed now or should be rate-limited."
 [rate-limiter k]
 (allow? rate-limiter k (now)))

(defn insert-now
 "Updates the rate limiter with key `k` at time `ts`. If `v` is provided, it's
 cached if and only if `k` is not allowed."
 ([rate-limiter k]
  (insert rate-limiter k (now)))
 ([rate-limiter k v]
  (insert rate-limiter k (now) v)))

(defn insert-allow?-now
 "Updates the rate limiter with key `k` at the current timestamp. If `v` is
 provided, it's cached if and only if `k` is not allowed.

 Returns a triple containing:
 1. The modified rate limiter.
 2. A flag saying if the key should be allowed (true) or rate-limited (false).
 3. The previously cached value for `k`, if it exists and is dropped by the
    insert operation (this happens when the previous insert for `k` was
    rate-limited and a value was provided for caching).

 This fn is really just a more convenient/efficient way to run:
 ```
 (let [allow? (allow-now? this k ts)]
   [(insert-now this k ts v) allow? (if allow? (get-cached this k))])
 ```"
 ([rate-limiter k]
  (insert-allow? rate-limiter k (now)))
 ([rate-limiter k v]
  (insert-allow? rate-limiter k (now) v)))

(defn take-expired-now
  "Returns a sequence of all cached values that are currently expired, as
  key-value pairs."
  [rate-limiter]
  (take-expired rate-limiter (now)))

(defn drop-expired-now
  "Updates the rate limiter such that the state for all keys that are currently
  expired are removed."
  [rate-limiter]
  (drop-expired rate-limiter (now)))

;;;; Stateful API ;;;;

(defn insert!
  "Inserts `k` into `*rl` at time `ts`. If `v` is provided, it is cached (if
  `k` is rate-limited)."
  ([*rl k ts]
   (swap! *rl insert k ts)
   nil)
  ([*rl k ts v]
   (swap! *rl insert k ts v)
   nil))

(defn insert-now!
  "Inserts `k` into `*rl` at the current timestamp. If `v` is provided and `k`
  gets rate-limited, `v` gets cached."
  ([*rl k]
   (insert! *rl k (now)))
  ([*rl k v]
   (insert! *rl k (now) v)))

(defn insert-allow?!
  "Inserts `k` into `*rl` at time `ts`. If `v` is provided, it is cached (if `k`
  is rate-limited).
  Returns whether the key is allowed or should be rate-limited."
  ([*rl k ts]
   (let [*r (atom nil)]
     (swap! *rl #(let [[rl' & r] (insert-allow? % k ts)] (reset! *r r) rl'))
     @*r))
  ([*rl k ts v]
   (let [*r (atom nil)]
     (swap! *rl #(let [[rl' & r] (insert-allow? % k ts v)] (reset! *r r) rl'))
     @*r)))

(defn insert-allow?-now!
  "Inserts `k` into `*rl` at the current timestamp. If `v` is provided, it is
  cached (if `k` is rate-limited).
  Returns whether the key is allowed or should be rate-limited."
  ([*rl k]
   (insert-allow?! *rl k (now)))
  ([*rl k v]
   (insert-allow?! *rl k (now) v)))

(defn truncate!
  "Removes and returns all keys in `*rl` that are expired at time `ts`."
  [*rl ts]
  (let [*r (atom nil)]
    (swap! *rl (fn [rl] (reset! *r (take-expired rl ts)) (drop-expired rl ts)))
    @*r))

(defn truncate-now!
  "Removes all keys in `*rl` that are expired at the current time. Returns a
  list of key-value pairs "
  [*rl]
  (truncate! *rl (now)))
