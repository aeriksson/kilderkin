(ns mostlyfunctional.rate-limit-performance-test
  "Performance tests for rate limiters."
  (:require [criterium.core :refer [with-progress-reporting quick-bench]]
            [mostlyfunctional.rate-limit :as rl])
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
    :insert   (first (rl/allow-ts? rl ts k v))
    :truncate (first (rl/truncate-ts rl ts))))

(defn exec-ops
  [rl ops]
  (reduce exec-op rl ops))

(defn benchmark-sliding-window-rate-limiter
  [n-ops n-keys ts-range-ms window-ms]
  (let [rate-limiter (rl/sliding-window-rate-limiter window-ms)
        ops          (doall (gen-ops n-ops n-keys ts-range-ms))]
    (with-progress-reporting
      (quick-bench (exec-ops rate-limiter ops) :verbose))))

(defn benchmark-tumbling-window-rate-limiter
  [n-ops n-keys ts-range-ms window-ms]
  (let [rate-limiter (rl/tumbling-window-rate-limiter window-ms)
        ops          (gen-ops n-ops n-keys ts-range-ms)]
    (with-progress-reporting
      (quick-bench (exec-ops rate-limiter ops) :verbose))))

(defn benchmark-hopping-window-rate-limiter
  [n-ops n-keys ts-range-ms window-ms hop-ms]
  (let [rate-limiter (rl/hopping-window-rate-limiter window-ms hop-ms)
        ops          (doall (gen-ops n-ops n-keys ts-range-ms))]
    (with-progress-reporting
      (quick-bench (exec-ops rate-limiter ops) :verbose))))

(defn run-default-sliding-window-rate-limiter-test
  []
  (prn "~~~~ Testing sliding window rate limiter ~~~~")
  (benchmark-sliding-window-rate-limiter 100000 10 100000 10000))

(defn run-default-tumbling-window-rate-limiter-test
  []
  (prn "~~~~ Testing tumbling window rate limiter ~~~~")
  (benchmark-tumbling-window-rate-limiter 100000 10 100000 10000))

(defn run-default-hopping-window-rate-limiter-test
  []
  (prn "~~~~ Testing hopping window rate limiter ~~~~")
  (benchmark-hopping-window-rate-limiter 100000 10 100000 10000 5000))

(defn run-default-tests
  []
  (run-default-sliding-window-rate-limiter-test)
  (run-default-tumbling-window-rate-limiter-test)
  (run-default-hopping-window-rate-limiter-test))

(defn -main
  []
  (run-default-tests))
