(ns kilderkin.core-test
  "Unit tests for `kilderkin.core`."
  (:require [clojure.test :refer [deftest testing is]]
            [kilderkin.core :as k]))

(deftest insert-test
  (doseq [:let               [win 1000]
          [[i i' i'' o] cfg] [[[0 1 (dec win) win] {:window-ms win}]
                              [[100 200 (dec win) win]
                               {:window-ms   win
                                :window-type :tumbling}]
                              [[1 20 (dec win) win]
                               {:window-ms   win
                                :window-type :hopping
                                :hop-ms      100}]]]
    (testing "`insert` and `allow?` work as expected"
      (testing "for max-per-window = 1"
        (doseq [k    [:foo "bar" 'baz]
                :let [rl (k/rate-limiter cfg)]]
          (is (k/allow? rl k i)
              "the first call is allowed")
          (is (not (k/allow? (k/insert rl k i) k i'))
              "another call within the window is not allowed")
          (is (k/allow? (k/insert rl k i) k o)
              "a call outside the window is allowed")))
      (testing "for max-per-window = 2"
        (doseq [k    [:foo]
                :let [rl (k/rate-limiter (assoc cfg :max-per-window 2))]]
          (is (k/allow? rl k i)
              "the first call is allowed")
          (is (k/allow? (k/insert rl k i) k i')
              "a second call within the window is allowed")
          (is (not (k/allow? (k/insert (k/insert rl k i) k i') k i''))
              "a third call within the window is not allowed")
          (is (-> rl (k/insert k i) (k/insert k i') (k/allow? k o))
              "a third call after the window is allowed")
          (testing "calls at the same timestamp work"
            (is (k/allow? (k/insert rl k i) k i)
                "a second call within the window is allowed")
            (is (-> rl (k/insert k i) (k/insert k i) (k/allow? k i) not)
                "a third call within the window is not allowed")))))))

(deftest multiple-key-test
  (testing "multiple keys don't interfere"
    (doseq [:let [win 100
                  t   1
                  t'  2
                  t'' 3
                  k   :foo
                  k'  :bar
                  k'' :baz
                  cfg {:window-ms win, :max-per-window 2}]
            cfg' [cfg
                  (assoc cfg :window-type :tumbling)
                  (assoc cfg :window-type :hopping :hop-ms 10)]
            :let [rl  (k/rate-limiter cfg')
                  rl' (-> rl
                          (k/insert k t)
                          (k/insert k' t')
                          (k/insert k'' t''))]]
      (testing (str "for window type " (:window-type cfg))
        (is (not (k/allow? (k/insert rl' k t') k t'')))
        (is      (k/allow? (k/insert rl' k t') k' t''))
        (is      (k/allow? (k/insert rl' k t') k'' t''))
        (is (not (k/allow? (k/insert (k/insert rl' k t') k' t) k t'')))
        (is (not (k/allow? (k/insert (k/insert rl' k t') k' t) k' t'')))
        (is      (k/allow? (k/insert (k/insert rl' k t') k' t) k'' t''))))))

(deftest caching-test
  (doseq [:let [win 100]
          cfg  [{:window-ms win, :window-type :sliding}
                {:window-ms win, :window-type :tumbling}
                {:window-ms win, :window-type :hopping, :hop-ms 10}]]
    (testing (str "for window type " (:window-type cfg))
      (testing "without reduce-fn"
        (let [t  1
              k  :foo
              v  `bar
              rl (k/rate-limiter cfg)]
          (is (nil? (k/get-cached rl k))
              "get-cached returns nothing for keys not in the rate limiter")
          (is (nil? (k/get-cached (k/insert rl k t v) k))
              "nothing is cached for allowed inserts")
          (is (= v (k/get-cached (k/insert (k/insert rl k t) k (inc t) v) k))
              "the value is cached for the first blocked insert")
          (is (= v (-> rl
                       (k/insert k t)
                       (k/insert k t 1)
                       (k/insert k t v)
                       (k/get-cached k)))
              "the last blocked value is cached for multiple blocked inserts"))
        (testing "with reduce-fn"
          (let [t  1
                k  :foo
                v  123
                rl (k/rate-limiter (assoc cfg :reduce-fn str))]
            (is (nil? (k/get-cached (k/insert rl k t v) k))
                "nothing is cached for allowed inserts")
            (is (= v (-> rl (k/insert k t) (k/insert k t v) (k/get-cached k)))
                "the value is cached for the first blocked insert")
            (is (= (str 1 v) (-> rl
                                 (k/insert k t)
                                 (k/insert k t 1)
                                 (k/insert k t v)
                                 (k/get-cached k)))
                "multiple cached values are reduced")))
        (testing "with reduce-fn and init-val"
          (let [t  1
                k  :foo
                v  123
                rl (k/rate-limiter (assoc cfg :reduce-fn str :init-val 1))]
            (is (nil? (k/get-cached (k/insert rl k t v) k))
                "nothing is cached for allowed inserts")
            (is (= (str 1 v) (-> rl
                                 (k/insert k t)
                                 (k/insert k t v)
                                 (k/get-cached k)))
                "the value is cached for the first blocked insert")
            (is (= (str 12 v) (-> rl
                                  (k/insert k t)
                                  (k/insert k t 2)
                                  (k/insert k t v)
                                  (k/get-cached k)))
                "multiple cached values are reduced")))))))

(deftest expired-test
  (testing "expiring works"
    (doseq [:let     [win 1000
                      t   0
                      k   :foo
                      k'  :bar
                      v   12
                      v'  23]
            [cfg t'] [[{:window-ms win} (inc t)]
                      [{:window-ms win, :window-type :tumbling} (+ t win)]
                      [{:window-ms win, :window-type :hopping, :hop-ms 100}
                       (+ t win)]]
            :let     [rl (k/rate-limiter cfg)]]
      (testing (str "for window type " (:window-type cfg))
        (is (= [] (k/take-expired (k/insert rl k t) (+ t win)))
            "take-expired returns nothing when nothing is cached")
        (let [rl' (k/insert (k/insert rl k t) k t v)]
          (is (= [] (k/take-expired rl' (dec (+ t win))))
              "take-expired returns nothing when values aren't expired")
          (is (= rl' (k/drop-expired rl' (dec (+ t win))))
              "drop-expired does nothing when values aren't expired"))
        (let [rl'  (-> rl
                       (k/insert k t)
                       (k/insert k (inc t) v)
                       (k/insert k' t')
                       (k/insert k' (inc t') v'))
              rl'' (-> rl
                       (k/insert k' t')
                       (k/insert k' (inc t') v'))]
          (is (= [[k v]] (k/take-expired rl' (+ t win)))
              "take-expired only takes expired values")
          (is (= [[k v] [k' v']] (k/take-expired rl' (+ t' win)))
              "take-expired returns values in order of expiry")
          (is (= rl'' (k/drop-expired rl' (+ t win)))
              "drop-expired only drops expired values")
          (is (= rl (k/drop-expired rl' (+ t' win)))
              "drop-expired drops all expired values"))))))

(deftest insert-allow?-test
  (doseq [:let [win 1000]
          cfg  [{:window-ms win, :max-per-window 2}
                {:window-ms win, :max-per-window 2, :window-type :tumbling}
                {:window-ms      100
                 :max-per-window 2
                 :window-type    :hopping
                 :hop-ms         10}]
          :let [rl (k/rate-limiter cfg)]]
    (testing (str "for window type " (:window-type cfg))
      (doseq [:let [t 1
                    k :foo]
              rl' [rl
                   (k/insert rl k t)
                   (k/insert (k/insert rl k t) k t)]]
        (is (= (first (k/insert-allow? rl' k t)) (k/insert rl' k t))
            "insert-allow? and insert are consistent")
        (is (= (second (k/insert-allow? rl' k t)) (k/allow? rl' k t))
            "insert-allow? and allow? are consistent"))
      (let [t 1
            k :foo
            v 'bar]
        (is (-> (k/rate-limiter {:window-ms win})
                (k/insert k t)
                (k/insert k t)
                (k/insert k t v)
                (k/insert-allow? k t)
                (get 2)
                nil?)
            "insert-allow? doesn't return cached keys when they aren't expired")
        (is (-> (k/rate-limiter {:window-ms win})
                (k/insert k t)
                (k/insert k t)
                (k/insert k t v)
                (k/insert-allow? k (+ t win))
                (get 2)
                (= v))
            "insert-allow? returns cached keys when they get expired")))))

(defn nowish?
  [ts]
  (let [now #?(:clj (System/currentTimeMillis)) #?(:cljs (.getTime (js/Date.)))]
    (<= 0 (- now ts) 10)))

(deftest wall-clock-api-test
  (let [rl {}
        k  :foo
        v  :bar]
    (testing "allow-now? works as expected"
      (let [*called? (atom false)]
        (with-redefs [k/allow? (fn [rl' k' t]
                                 (is (= rl rl'))
                                 (is (= k k'))
                                 (is (nowish? t))
                                 (reset! *called? true))]
          (k/allow-now? rl k)
          (is @*called? "allow? gets called"))))
    (testing "insert-now works as expected"
      (testing "without a value"
        (let [*called? (atom false)]
          (with-redefs [k/insert (fn [rl' k' t]
                                   (is (= rl rl'))
                                   (is (= k k'))
                                   (is (nowish? t))
                                   (reset! *called? true))]
            (k/insert-now rl k)
            (is @*called? "insert gets called"))))
      (testing "with a value"
        (let [*called? (atom false)]
          (with-redefs [k/insert (fn [rl' k' t v']
                                   (is (= rl rl'))
                                   (is (= k k'))
                                   (is (nowish? t))
                                   (is (= v v'))
                                   (reset! *called? true))]
            (k/insert-now rl k v)
            (is @*called? "insert gets called")))))
    (testing "insert-allow?-now works as expected"
      (testing "without a value"
        (let [*called? (atom false)]
          (with-redefs [k/insert-allow? (fn [rl' k' t]
                                          (is (= rl rl'))
                                          (is (= k k'))
                                          (is (nowish? t))
                                          (reset! *called? true))]
            (k/insert-allow?-now rl k)
            (is @*called? "insert-allow? gets called"))))
      (testing "with a value"
        (let [*called? (atom false)]
          (with-redefs [k/insert-allow? (fn [rl' k' t v']
                                          (is (= rl rl'))
                                          (is (= k k'))
                                          (is (nowish? t))
                                          (is (= v v'))
                                          (reset! *called? true))]
            (k/insert-allow?-now rl k v)
            (is @*called? "insert-allow? gets called")))))
    (testing "take-expired-now works as expected"
      (let [*called? (atom false)]
        (with-redefs [k/take-expired (fn [rl' t]
                                       (is (= rl rl'))
                                       (is (nowish? t))
                                       (reset! *called? true))]
          (k/take-expired-now rl)
          (is @*called? "take-expired gets called"))))
    (testing "drop-expired-now works as expected"
      (let [*called? (atom false)]
        (with-redefs [k/drop-expired (fn [rl' t]
                                       (is (= rl rl'))
                                       (is (nowish? t))
                                       (reset! *called? true))]
          (k/drop-expired-now rl)
          (is @*called? "drop-expired gets called"))))))

(deftest stateful-api-test
  (let [rl {}
        t  123
        k  :foo
        v  :bar]
    (testing "insert! works as expected"
      (testing "without a value"
        (let [*rl (atom rl)]
          (with-redefs [k/insert (fn [rl' k' t']
                                   (is (= rl rl'))
                                   (is (= t t'))
                                   (is (= k k'))
                                   :ok)]
            (k/insert! *rl k t)
            (is (= :ok @*rl) "insert gets called"))))
      (testing "without a value"
        (let [*rl (atom rl)]
          (with-redefs [k/insert (fn [rl' k' t' v']
                                   (is (= rl rl'))
                                   (is (= k k'))
                                   (is (= t t'))
                                   (is (= v v'))
                                   :ok)]
            (k/insert! *rl k t v)
            (is (= :ok @*rl) "insert gets called")))))
    (testing "insert-now! works as expected"
      (testing "without a value"
        (let [*rl (atom rl)]
          (with-redefs [k/insert (fn [rl' k' t]
                                   (is (= rl rl'))
                                   (is (nowish? t))
                                   (is (= k k'))
                                   :1)]
            (is (nil? (k/insert-now! *rl k))
                "nil is returned")
            (is (= :1 @*rl)
                "the atom value is updated"))))
      (testing "without a value"
        (let [*rl (atom rl)]
          (with-redefs [k/insert (fn [rl' k' t v']
                                   (is (= rl rl'))
                                   (is (= k k'))
                                   (is (nowish? t))
                                   (is (= v v'))
                                   :1)]
            (is (nil? (k/insert-now! *rl k v))
                "nil is returned")
            (is (= :1 @*rl)
                "the atom value is updated")))))
    (testing "insert-allow?! works as expected"
      (testing "without a value"
        (let [*rl (atom rl)]
          (with-redefs [k/insert-allow? (fn [rl' k' t']
                                          (is (= rl rl'))
                                          (is (= t t'))
                                          (is (= k k'))
                                          [:1 :2 :3])]
            (is (= [:2 :3] (k/insert-allow?! *rl k t))
                "the allow flag and cached value are returned")
            (is (= :1 @*rl)
                "the atom value is updated"))))
      (testing "without a value"
        (let [*rl (atom rl)]
          (with-redefs [k/insert-allow? (fn [rl' k' t' v']
                                          (is (= rl rl'))
                                          (is (= k k'))
                                          (is (= t t'))
                                          (is (= v v'))
                                          [:1 :2 :3])]
            (is (= [:2 :3] (k/insert-allow?! *rl k t v))
                "the allow flag and cached value are returned")
            (is (= :1 @*rl)
                "the atom value is updated")))))
    (testing "insert-allow?-now! works as expected"
      (testing "without a value"
        (let [*rl (atom rl)]
          (with-redefs [k/insert-allow? (fn [rl' k' t]
                                          (is (= rl rl'))
                                          (is (nowish? t))
                                          (is (= k k'))
                                          [:1 :2 :3])]
            (is (= [:2 :3] (k/insert-allow?-now! *rl k))
                "the allow flag and cached value are returned")
            (is (= :1 @*rl)
                "the atom value is updated"))))
      (testing "without a value"
        (let [*rl (atom rl)]
          (with-redefs [k/insert-allow? (fn [rl' k' t v']
                                          (is (= rl rl'))
                                          (is (= k k'))
                                          (is (nowish? t))
                                          (is (= v v'))
                                          [:1 :2 :3])]
            (is (= [:2 :3] (k/insert-allow?-now! *rl k v))
                "the allow flag and cached value are returned")
            (is (= :1 @*rl)
                "the atom value is updated")))))
    (testing "truncate works as epxected"
      (let [*rl (atom rl)]
        (with-redefs [k/take-expired (fn [rl' t']
                                       (is (= rl rl'))
                                       (is (= t t'))
                                       :foo)
                      k/drop-expired (fn [rl' t']
                                       (is (= rl rl'))
                                       (is (= t t'))
                                       :bar)]
          (is (= :foo (k/truncate! *rl t))
              "the return value of take-expired is returned")
          (is (= :bar @*rl)
              "the atom is set to the return value of drop-expired"))))
    (testing "truncate works as epxected"
      (let [*rl (atom rl)]
        (with-redefs [k/take-expired (fn [rl' t]
                                       (is (= rl rl'))
                                       (is (nowish? t))
                                       :foo)
                      k/drop-expired (fn [rl' t]
                                       (is (= rl rl'))
                                       (is (nowish? t))
                                       :bar)]
          (is (= :foo (k/truncate-now! *rl))
              "the return value of take-expired is returned")
          (is (= :bar @*rl)
              "the atom is set to the return value of drop-expired"))))))
