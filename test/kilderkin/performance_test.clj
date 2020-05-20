(ns kilderkin.performance-test
  "Performance tests for Kilderkin rate limiters."
  (:require [criterium.core :refer [with-progress-reporting quick-bench]]
            [kilderkin.core :as k])
  (:gen-class))

(defn gen-op
  [n-keys ts-range-ms]
  (if (< (rand-int 100) 5)
    [(rand-int ts-range-ms) :truncate]
    [(rand-int ts-range-ms) :insert (rand-int n-keys) (rand-int 10)]))

(defn gen-ops
  [n-ops n-keys ts-range-ms]
  (sort-by first (repeatedly n-ops #(gen-op n-keys ts-range-ms))))

(defn exec-op
  [rl [ts op k v]]
  (case op
    :insert   (k/insert rl ts k v)
    :truncate (k/drop-expired rl ts)))

(defn exec-ops
  [rl ops]
  (reduce exec-op rl ops))

(defn benchmark-sliding-window-rate-limiter
  [n-ops n-keys ts-range-ms window-ms max-per-window]
  (let [rate-limiter (k/rate-limiter {:window-ms      window-ms
                                      :max-per-window max-per-window})
        ops          (doall (gen-ops n-ops n-keys ts-range-ms))]
    (with-progress-reporting
      (quick-bench (exec-ops rate-limiter ops) :verbose))))

(defn benchmark-tumbling-window-rate-limiter
  [n-ops n-keys ts-range-ms window-ms max-per-window]
  (let [rate-limiter (k/rate-limiter {:window-type    :tumbling
                                      :window-ms      window-ms
                                      :max-per-window max-per-window})
        ops          (gen-ops n-ops n-keys ts-range-ms)]
    (with-progress-reporting
      (quick-bench (exec-ops rate-limiter ops) :verbose))))

(defn benchmark-hopping-window-rate-limiter
  [n-ops n-keys ts-range-ms window-ms hop-ms max-per-window]
  (let [rate-limiter (k/rate-limiter {:window-type    :hopping
                                      :window-ms      window-ms
                                      :hop-ms         hop-ms
                                      :max-per-window max-per-window})
        ops          (doall (gen-ops n-ops n-keys ts-range-ms))]
    (with-progress-reporting
      (quick-bench (exec-ops rate-limiter ops) :verbose))))

(defn run-default-sliding-window-rate-limiter-test
  []
  (prn "~~~~ Testing sliding window rate limiter ~~~~")
  (benchmark-sliding-window-rate-limiter 100000 10 100000 10000 1000))

(defn run-default-tumbling-window-rate-limiter-test
  []
  (prn "~~~~ Testing tumbling window rate limiter ~~~~")
  (benchmark-tumbling-window-rate-limiter 100000 10 100000 10000 10))

(defn run-default-hopping-window-rate-limiter-test
  []
  (prn "~~~~ Testing hopping window rate limiter ~~~~")
  (benchmark-hopping-window-rate-limiter 100000 10 100000 10000 1000 10))

(defn run-default-tests
  []
  (run-default-sliding-window-rate-limiter-test)
  (run-default-tumbling-window-rate-limiter-test)
  (run-default-hopping-window-rate-limiter-test))

(defn -main
  []
  (run-default-tests))
