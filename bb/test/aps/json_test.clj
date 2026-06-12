(ns aps.json-test
  (:require [aps.json :as aps-json]
            [clojure.test :refer [deftest is]]))

(deftest strips-nil-without-dropping-empty-collections
  (is (= {:a [] :c {:e {}}}
         (aps-json/strip-nil {:a [] :b nil :c {:d nil :e {}}}))))

(deftest strips-empty-only-for-selected-keys
  (is (= {:scenarios [] :nested {:kept []}}
         (aps-json/strip-empty-keys #{:background :parameters}
                                    {:background []
                                     :parameters []
                                     :scenarios []
                                     :nested {:parameters [] :kept []}}))))
