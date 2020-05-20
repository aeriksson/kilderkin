<p align="center">
  <img height="400" src="assets/logo.svg">
</p>

<p align="center">
  <a href="https://clojars.org/kilderkin">
    <img src="https://github.com/aeriksson/kilderkin/workflows/CI/badge.svg">
  </a>
  <a href="https://clojars.org/aeriksson/kilderkin">
    <img src="https://img.shields.io/clojars/v/aeriksson/kilderkin.svg">
  </a>
  <a href="https://cljdoc.org/d/aeriksson/kilderkin/CURRENT">
    <img src="https://cljdoc.org/badge/aeriksson/kilderkin">
  </a>
</p>
<p align="center">
  Fast, flexible, immutable data structures for rate limiting.
</p>

## Why?
Rate limiting is ~~fun~~ useful! It's the difference between a good night's sleep and being forced to spend the entire night manually cleaning out millions of events from your message queues. It's the difference between taking a nice hot bath and spending all your money repairing water damage. It's the difference between eating ice cream and developing diabetes.

## Features
- Immutables data structures and side-effect-free operations.
- Stateful wrapper API if that's what you prefer.
- Configurable and tunable windowing and bucketing.
- Use arbitrary timestamp — or wall-clock time if that's what you prefer.
- Cache and aggregate data for rate-limited keys.
- Almost no dependencies, just [clojure.data/priority-map](https://github.com/clojure/data.priority-map/).
- Supports both Clojure and ClojureScript.

## Quick Start
Add the necessary dependency to your `project.clj` (`[aeriksson/kilderkin "0.1.0"]`) or `deps.edn` (`{aeriksson/kilderkin {:mvn/version "0.1.0"}}`).

Require `kilderkin.core`:
```clojure
(require '[kilderkin.core :as k])
```

Create a rate-limiter:
```clojure
(def rate-limiter (k/rate-limiter {:window-ms 60000, :max-per-window 2}))
```

Insert a few elements:
```clojure
(def rate-limiter' (k/insert-now (k/insert-now rate-limiter :foo) :foo))
```

Check if some keys should be rate-limited:
```clojure
(k/allow-now? rate-limiter' :foo)
=> false

(k/allow-now? rate-limiter' :bar)
=> true
```

Use custom timestamps:
```clojure
(def rate-limiter (k/insert rate-limiter :baz 123123))
(k/allow? rate-limiter :baz 456456)
```
All functions exist in both a variant that takes a custom timestamp and a variant that uses the current system timestamp (suffixed with `-now`).

Note that the provided rate limiters aren't designed to work when keys are inserted wildly out of order — try to keep the timestamps for any given key (mostly) increasing over time.

Inserting and checking a key can be done in a single operaton — this is faster than calling `insert` and `update` separately:
```clojure
(k/insert-allow? rate-limiter :foo 123)
=> [(k/insert rate-limiter :foo 123) (k/allow? rate-limiter :foo 123)]
```

### Caching
Cache rate-limited data (for when you want to keep track of the stuff that gets rate-limited):
```clojure
(def rate-limiter
  (reduce #(k/insert %1 :foo 1000 %2)
          (k/rate-limiter {:window-ms 60000, :max-per-window 2})
          (range 5)))

(k/get-cached rate-limiter :foo)
=> 4

(k/take-expired rate-limiter 1000)
=> []

(k/take-expired rate-limiter 100000)
=> [[:foo 4]]

; Optionally specify a function for aggregating cached values:
(def rate-limiter
  (k/rate-limiter
   {:window-ms 60000, :max-per-window 2, :reduce-fn conj, :init-val #{}}))

(def rate-limiter
  (reduce #(k/insert %1 :foo 1000 %2) rate-limiter (range 5)))

(k/take-expired rate-limiter 100000)
=> [[:foo #{2 3 4}]]
```

### Stateful API
Because rate limiting is sort of a naturally stateful process, `kilderkin` also provides a 'stateful API' — a collection of utility functions for dealing with rate limiters wrapped in atoms:

Example:
```clojure
(def *rate-limiter (atom (k/rate-limiter {:window-ms 1000, :max-per-window 2})))

(k/insert-now! *rate-limiter :foo)
=> nil

(k/allow-now? @*rate-limiter :foo)
=> true

(k/insert-allow?-now! *rate-limiter :foo)
=> [true]

(k/insert-allow?-now! *rate-limiter :foo "cache-me")
=> [false]

(Thread/sleep 1000)
(k/truncate-now! *rate-limiter)
=> [[:foo "cache-me"]]

(k/truncate-now! *rate-limiter)
=> []
```

### Confguration
There are currently three different rate limiters available, corresponding to different windowing methods.  Which is the most appropriate for your use-case depends on your performance and accuracy requirements.

All rate limiters can be configured with the following keys:
- `:window-ms`: the size of the rate limiting window, in milliseconds.
- `:max-per-bucket`: the maximum number of allowed inserts for any given key
                     within the window. Defaults to `1`.

#### Sliding window rate limiter
The sliding window rate limiter is the default (it's used when `rate-limiter` is called with no rate limiter type specified) and the most accurate.

It works by tracking — on a per-key basis — all timestamps (at most `:window-ms` old) at which `insert` has been called for any given key. This enables it to always produce accurate results, but it also incurs a hefty memory and CPU overhead when large numbers of `insert`s are tracked.

Example:
```clojure
; Sliding windows are always accurate:
(def w (rand-int 10000))
(def n (rand-int w))
(def rate-limiter
  (-> (k/rate-limiter {:window-ms w})
      (k/insert :foo n)))

(k/allow? rate-limiter :foo (dec (+ n w)))
=> false

(k/allow? rate-limiter :foo (+ n w))
=> true

; ...but they are slow when there are lots of timestamps to track:
; (let [n 1e6]
;   (reduce #(k/insert %1 :foo %2)
;           ; you should pick another rate limiter here:
;           (k/rate-limiter {:window-ms n, :max-per-window n})
;           (range n)))
```

Use when: `:max-per-window` is low and accuracy is important.

#### Tumbling window rate limiter
One way to speed things up in the types of scenarios where sliding windows perform poorly is to group events into buckets, and track event counts per bucket instead. This is how the tumbling window rate limiter works. For any given key, it keeps track of the most recent bucket (defined as the interval `[n * window-ms, (n + 1) * window-ms[` that contains the most recently inserted timestamp) — together with the number of inserts in that bucket. When an insert is performed in new bucket, the previous bucket is discarded.

Tumbling rate limiters support the additional config key `:stagger-bucket-offsets`.  This is a boolean flag that — if set to `true` — ensures that buckets are offset by a random(ish) key-dependent number. The point of this is to prevent the rate limiter from becoming overly 'bursty' when lots of keys are being limited (if `:stagger-bucket-offsets` is not set, all buckets roll at the same time, at which point all rate-limited keys are simultaneously allowed).

Example:
```clojure
; Tumbling windows are fast, and behave nicely as long as timestamps are aligned with buckets:
(-> (k/rate-limiter {:type :tumbling-window, :window-ms 10, :max-per-window 2})
    (k/insert :foo 1)
    (k/insert :foo 2)
    (k/allow? :foo 3)) ; => false

; ...but they are innacurate around the bucket edges:
(-> (k/rate-limiter {:type :tumbling-window, :window-ms 10, :max-per-window 2})
    (k/insert :foo 8)
    (k/insert :foo 9)
    (k/allow? :foo 10)) ; => true
```

Use when: `:max-per-window` is high, and low latency is more important than
absolute fairness.

#### Hopping window rate limiter
But what if sliding windows are too slow for your application, but tumbling windows too inaccurate? Lucky for you, there are also hopping windows. These are bucketed just like tumbling windows, but the bucket size — which is specified via `:hop-ms` — is decoupled from the window size (in other words, the bucket for `t` is defined as the interval `[n * hop-ms, (n + 1) * hop-ms[` containing `t`).

The bucket size is configured via the `:hop-ms` key. The lower this value is, the more accurate the rate limiter. The higher it is, the better performance will be for large values of `:max-per-window`.

Of course, `:stagger-bucket-offsets` is supported for hopping windows as well.

Example:
```clojure
; When `window-ms` and `hop-ms` are equal, hopping windows act like tumbling windows.
(k/rate-limiter {:type :hopping-window, :window-ms 1000, :hop-ms 1000})

; When `hop-ms` is `1`, hopping windows act like sliding windows.
(k/rate-limiter {:type :hopping-window, :window-ms 1000, :hop-ms 1})

; The sweet spot is usually somewhere inbetween:
(k/rate-limiter {:type :hopping-window, :window-ms 1000, :hop-ms 100})
```

Use when: you want better control over the performance-accuracy tradeoff.

## Contributing
Please use the [project's GitHub issues page](https://github.com/aeriksson/kilderkin/issues) for questions, ideas, etc. Pull requests are welcome!

## License

Distributed under the [MIT license](LICENSE.md).

Copyright © 2020 André Eriksson
