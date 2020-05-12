(ns mostlyfunctional.rate-limit-test
  "Unit tests for rate limiters."
  (:require [clojure.test :refer [deftest testing is]]
            [mostlyfunctional.rate-limit :as rl]))

;;;; Utils ;;;;

(defn run-allow?-test
  "Verifies that the given rate-limiter, when `k -> v` is inserted at time `ts`,
  returns `[_ exp-allow? exp-v]`. Returns the updated rate limiter state
  resulting from the operation."
  [rl [ts k v exp-allow? exp-v]]
  (testing (str "inserting @" ts ": " k " -> " v)
    (let [[rl' allow? v] (rl/allow-ts? rl ts k v)]
      (is (= exp-allow? allow?)
          (str "The operation is " (if exp-allow? "allowed." "blocked.")))
      (is (= exp-v v) (str "The returned value is `" (pr-str exp-v) "`."))
      rl')))

;;;; Tests ;;;;

(deftest sliding-window-test
  (testing "`allow?` works"
    (testing "without reduce-fn"
      (reduce run-allow?-test
              (rl/sliding-window-rate-limiter 123 2)
              [[0   :a 1 true]
               [100 :a 2 true]
               [100 :b 2 true]
               [123 :a 3 false]
               [124 :a 4 true  3]
               [124 :c 4 true]
               [223 :a 5 false]
               [224 :a 6 true  5]]))
    (testing "with reduce-fn"
      (reduce run-allow?-test
              (rl/sliding-window-rate-limiter 123 2 str)
              [[0   :a 1 true]
               [100 :a 2 true]
               [122 :a 3 false]
               [123 :a 4 false]
               [124 :a 5 true  "34"]
               [223 :a 6 false]
               [224 :a 7 true  "6"]]))
    (testing "with reduce-fn and init-val"
      (reduce run-allow?-test
              (rl/sliding-window-rate-limiter 123 2 str 1)
              [[0   :a 2 true]
               [100 :a 3 true]
               [122 :a 4 false]
               [123 :a 5 false]
               [124 :a 6 true  "145"]
               [223 :a 7 false]
               [224 :a 8 true  "17"]])))
  (testing "`truncate` works"))

(deftest tumbling-window-test
  (testing "`allow?` works"
    (testing "without reduce-fn"
      (reduce run-allow?-test
              (rl/tumbling-window-rate-limiter 123 2)
              [[0   :a 1 true]
               [100 :a 2 true]
               [123 :a 3 false]
               [124 :a 4 true  3]
               [125 :a 5 true]
               [246 :a 6 false]
               [247 :a 7 true  6]]))
    (testing "with reduce-fn"
      (reduce run-allow?-test
              (rl/tumbling-window-rate-limiter 123 2 str)
              [[0   :a 1 true]
               [100 :a 2 true]
               [122 :a 3 false]
               [123 :a 4 false]
               [124 :a 5 true  "34"]
               [125 :a 6 true]
               [246 :a 7 false]
               [247 :a 8 true  "7"]]))
    (testing "with reduce-fn and init-val"
      (reduce run-allow?-test
              (rl/tumbling-window-rate-limiter 123 2 str 1)
              [[0   :a 2 true]
               [100 :a 3 true]
               [122 :a 4 false]
               [123 :a 5 false]
               [124 :a 6 true  "145"]
               [125 :a 7 true]
               [246 :a 8 false]
               [247 :a 9 true  "18"]])))
  (testing "`truncate` works"
    ()))

(deftest hopping-window-test
  (testing "`allow?` works"
    (testing "without reduce-fn"
      (reduce run-allow?-test
              (rl/hopping-window-rate-limiter 100 50 2)
              [[25  :a 1 true]
               [75  :a 2 true]
               [100 :a 3 false]
               [101 :a 4 true  3]
               [150 :a 5 false]
               [151 :a 6 true  5]]))
    (testing "with reduce-fn"
      (reduce run-allow?-test
              (rl/hopping-window-rate-limiter 100 50 2 str)
              [[25  :a 1 true]
               [75  :a 2 true]
               [99  :a 3 false]
               [100 :a 4 false]
               [101 :a 5 true  "34"]
               [150 :a 6 false]
               [151 :a 7 true  "6"]]))
    (testing "with reduce-fn and init-val"
      (reduce run-allow?-test
              (rl/hopping-window-rate-limiter 100 50 2 str 1)
              [[25  :a 1 true]
               [75  :a 2 true]
               [99  :a 3 false]
               [100 :a 4 false]
               [101 :a 5 true  "134"]
               [150 :a 6 false]
               [151 :a 7 true  "16"]])))
  (testing "`truncate` works"
    ))
