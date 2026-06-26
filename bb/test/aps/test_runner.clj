(ns aps.test-runner
  (:require [aps.dry-test]
            [aps.inference-test]
            [aps.gherkin-test]
            [aps.json-test]
            [aps.mutator-cli-test]
            [aps.mutation-test]
            [clojure.test :as test]))

(defn -main [& _]
  (let [{:keys [fail error]} (test/run-tests 'aps.inference-test
                                             'aps.gherkin-test
                                             'aps.dry-test
                                             'aps.json-test
                                             'aps.mutator-cli-test
                                             'aps.mutation-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
