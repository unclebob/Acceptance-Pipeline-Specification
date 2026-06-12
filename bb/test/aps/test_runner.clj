(ns aps.test-runner
  (:require [aps.dry-test]
            [aps.gherkin-test]
            [aps.mutation-test]
            [clojure.test :as test]))

(defn -main [& _]
  (let [{:keys [fail error]} (test/run-tests 'aps.gherkin-test
                                             'aps.dry-test
                                             'aps.mutation-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
