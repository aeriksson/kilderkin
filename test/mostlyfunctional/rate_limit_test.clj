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
               [122 :a 3 false]
               [123 :a 4 true  3]
               [124 :c 4 true]
               [222 :a 5 false]
               [223 :a 6 true  5]]))
    (testing "with reduce-fn"
      (reduce run-allow?-test
              (rl/sliding-window-rate-limiter 123 2 str)
              [[0   :a 1 true]
               [100 :a 2 true]
               [121 :a 3 false]
               [122 :a 4 false]
               [123 :a 5 true  "34"]
               [222 :a 6 false]
               [223 :a 7 true  "6"]]))
    (testing "with reduce-fn and init-val"
      (reduce run-allow?-test
              (rl/sliding-window-rate-limiter 123 2 str 1)
              [[0   :a 2 true]
               [100 :a 3 true]
               [121 :a 4 false]
               [122 :a 5 false]
               [123 :a 6 true  "145"]
               [222 :a 7 false]
               [223 :a 8 true  "17"]])))
  (testing "expiry works"
    (let [rl (-> (rl/sliding-window-rate-limiter 100 2)
                 (rl/update-key 0 :a)
                 (rl/set-cached :a 1)
                 (rl/update-key 10 :b)
                 (rl/set-cached :b 2)
                 (rl/update-key 20 :c))]
      (is (= (rl/take-expired rl 80) []))
      (is (= (rl/take-expired rl 100) [[:a 1]]))
      (is (= (rl/take-expired (rl/drop-expired rl 100) 100) []))
      (is (= (rl/take-expired rl 110) [[:a 1] [:b 2]]))
      (is (= (rl/take-expired (rl/drop-expired rl 100) 110) [[:b 2]]))
      (is (= (rl/take-expired rl 1000) [[:a 1] [:b 2] [:c nil]])))))

(deftest tumbling-window-test
  (testing "`allow?` works"
    (testing "without reduce-fn"
      (reduce run-allow?-test
              (rl/tumbling-window-rate-limiter 123 2)
              [[0   :a 1 true]
               [100 :a 2 true]
               [122 :a 3 false]
               [123 :a 4 true  3]
               [124 :a 5 true]
               [245 :a 6 false]
               [246 :a 7 true  6]]))
    (testing "with reduce-fn"
      (reduce run-allow?-test
              (rl/tumbling-window-rate-limiter 123 2 str)
              [[0   :a 1 true]
               [100 :a 2 true]
               [121 :a 3 false]
               [122 :a 4 false]
               [123 :a 5 true  "34"]
               [125 :a 6 true]
               [245 :a 7 false]
               [246 :a 8 true  "7"]]))
    (testing "with reduce-fn and init-val"
      (reduce run-allow?-test
              (rl/tumbling-window-rate-limiter 123 2 str 1)
              [[0   :a 2 true]
               [100 :a 3 true]
               [121 :a 4 false]
               [122 :a 5 false]
               [123 :a 6 true  "145"]
               [125 :a 7 true]
               [245 :a 8 false]
               [246 :a 9 true  "18"]])))
  (testing "expiry works"
    (let [rl (-> (rl/tumbling-window-rate-limiter 10)
                 (rl/update-key 0 :a)
                 (rl/set-cached :a 1)
                 (rl/update-key 9 :b)
                 (rl/set-cached :b 2)
                 (rl/update-key 10 :c))]
      (is (= (rl/take-expired rl 9) []))
      (is (= (sort (rl/take-expired rl 10)) [[:a 1] [:b 2]]))
      (is (= (sort (rl/take-expired rl 20)) [[:a 1] [:b 2] [:c nil]]))
      (is (= (rl/take-expired (rl/drop-expired rl 19) 20) [[:c nil]])))))

(deftest hopping-window-test
  (testing "`allow?` works"
    (testing "without reduce-fn"
      (reduce run-allow?-test
              (rl/hopping-window-rate-limiter 100 50 2)
              [[25  :a 1 true]
               [75  :a 2 true]
               [99  :a 3 false]
               [100 :a 4 true  3]
               [149 :a 5 false]
               [150 :a 6 true  5]]))
    (testing "with reduce-fn"
      (reduce run-allow?-test
              (rl/hopping-window-rate-limiter 100 50 2 str)
              [[25  :a 1 true]
               [75  :a 2 true]
               [98  :a 3 false]
               [99  :a 4 false]
               [100 :a 5 true  "34"]
               [149 :a 6 false]
               [150 :a 7 true  "6"]]))
    (testing "with reduce-fn and init-val"
      (reduce run-allow?-test
              (rl/hopping-window-rate-limiter 100 50 2 str 1)
              [[25  :a 1 true]
               [75  :a 2 true]
               [98  :a 3 false]
               [99  :a 4 false]
               [100 :a 5 true  "134"]
               [149 :a 6 false]
               [150 :a 7 true  "16"]])))
  (testing "expiry works"
    (let [rl (-> (rl/hopping-window-rate-limiter 10 5)
                 (rl/update-key 4 :a)
                 (rl/set-cached :a 1)
                 (rl/update-key 9 :b)
                 (rl/set-cached :b 2)
                 (rl/update-key 10 :c))]
      (is (= (rl/take-expired rl 9) []))
      (is (= (rl/take-expired rl 10) [[:a 1]]))
      (is (= (rl/take-expired rl 19) [[:a 1] [:b 2]]))
      (is (= (rl/take-expired rl 20) [[:a 1] [:b 2] [:c nil]]))
      (is (= (rl/take-expired (rl/drop-expired rl 19) 20) [[:c nil]])))))
