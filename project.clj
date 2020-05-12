(defproject mostlyfunctional/rate-limit "0.1.0"
  :description  "TODO!"
  :dependencies [[org.clojure/clojure           "1.10.1" :scope "provided"]
                 [org.clojure/data.priority-map "1.0.0"]
                 [criterium "0.4.5"]]
  :pedantic?    :abort
  :profiles     {:clj-kondo {:dependencies [[clj-kondo "2020.05.02"]]}})
