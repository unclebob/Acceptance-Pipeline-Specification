(ns aps.cli-test-helper)

(defn run-bb-task [args]
  (let [process (-> (ProcessBuilder. ^java.util.List (vec (cons "bb" args)))
                    (.redirectErrorStream true)
                    (.start))
        output (slurp (.getInputStream process))]
    (.waitFor process)
    {:exit (.exitValue process)
     :output output}))
