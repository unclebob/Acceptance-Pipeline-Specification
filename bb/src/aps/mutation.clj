(ns aps.mutation
  (:require [aps.gherkin :as gherkin]
            [aps.json :as aps-json]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io BufferedReader InputStreamReader OutputStreamWriter BufferedWriter]
           [java.nio.charset StandardCharsets]
           [java.security MessageDigest]
           [java.time Instant]
           [java.lang ProcessBuilder$Redirect]
           [java.util.concurrent Executors TimeUnit Callable]))

(defn- utf8-bytes [s] (.getBytes (str s) StandardCharsets/UTF_8))

(defn- fnv64a [& parts]
  (let [modulus (biginteger 18446744073709551616N)
        prime (biginteger 1099511628211N)]
    (reduce
     (fn [h b]
       (.mod (.multiply (.xor (biginteger h) (biginteger (bit-and b 0xff))) prime) modulus))
     (biginteger 14695981039346656037N)
     (mapcat #(concat (utf8-bytes %) [0]) parts))))

(defn- parse-int [s]
  (try
    (when (seq s) (Long/parseLong s))
    (catch Exception _ nil)))

(defn- parse-float [s]
  (try
    (when (str/includes? s ".")
      (let [f (Double/parseDouble s)]
        (when (and (not (Double/isInfinite f)) (not (Double/isNaN f))) f)))
    (catch Exception _ nil)))

(declare mutate-value mutate-comma-list strip-metadata-line parse-metadata-line
         metadata-implementation-hash metadata-path parse-worker-response
         validated-worker-response dither-char shift-char)

(defn- dither [path value]
  (if (empty? value)
    "x"
    (let [chars (vec value)
          index (int (mod (fnv64a path value) (count chars)))
          replacement (dither-char (chars index))]
      (apply str (assoc chars index replacement)))))

(defn- dither-char [ch]
  (or (shift-char ch \a \z \A)
      (shift-char ch \A \Z \a)
      \x))

(defn- shift-char [ch lower upper target-base]
  (when (<= (int lower) (int ch) (int upper))
    (char (+ (int target-base) (- (int ch) (int lower))))))

(defn- signed-delta [seed magnitude]
  (let [delta (inc (mod seed magnitude))]
    (if (zero? (mod seed 2)) (- delta) delta)))

(defn- mutate-int [seed trimmed]
  (when-let [i (parse-int trimmed)]
    (str (+ i (signed-delta seed 9)))))

(defn- mutate-float [seed trimmed]
  (when-let [f (parse-float trimmed)]
    (let [unsigned-delta (double (/ (+ (mod seed 900) 100) 100))
          delta (if (zero? (mod seed 2)) (- unsigned-delta) unsigned-delta)]
      (str (+ f delta)))))

(defn mutate-comma-list [path value trimmed seed]
  (when (str/includes? trimmed ",")
    (let [parts (mapv str/trim (str/split trimmed #","))
          index (int (mod seed (count parts)))]
      (str/join ", " (assoc parts index (mutate-value (str path "[]") (parts index)))))))

(defn- mutate-boolean-or-null [lower]
  (get {"true" "false"
        "false" "true"
        "null" "value"
        "nil" "value"
        "none" "value"}
       lower))

(defn mutate-value [path value]
  (let [trimmed (str/trim value)
        lower (str/lower-case trimmed)
        seed (fnv64a path value)]
    (or (mutate-comma-list path value trimmed seed)
        (mutate-boolean-or-null lower)
        (mutate-int seed trimmed)
        (mutate-float seed trimmed)
        (dither path value))))

(defn discover [feature]
  (vec
   (map-indexed
    (fn [i mutation] (assoc mutation :ID (str "m" (inc i))))
    (for [[scenario-index scenario] (map-indexed vector (:scenarios feature))
          [example-index example] (map-indexed vector (:examples scenario))
          key (sort (keys example))
          :let [original (get example key)
                path (format "$.scenarios[%d].examples[%d].%s" scenario-index example-index (name key))
                mutated (mutate-value path original)]
          :when (not= mutated original)]
      (array-map :Path path
                 :Description (format "%s: %s -> %s" path original mutated)
                 :Original original
                 :Mutated mutated
                 :scenario scenario-index
                 :example example-index
                 :key key)))))

(defn apply-mutation [feature mutation]
  (assoc-in feature [:scenarios (:scenario mutation) :examples (:example mutation) (:key mutation)]
            (:Mutated mutation)))

(defn- mutation-view [mutation]
  (array-map :ID (:ID mutation)
             :Path (:Path mutation)
             :Description (:Description mutation)
             :Original (:Original mutation)
             :Mutated (:Mutated mutation)))

(defn make-result [mutation runner-result]
  (let [status (get {"test_failure" "killed"
                     "test_success" "survived"
                     "infrastructure_error" "error"}
                    (:outcome runner-result)
                    "error")]
    (array-map :Mutation (mutation-view mutation)
               :Status status
               :Output (or (:output runner-result) "")
               :Error (or (:error runner-result) "")
               :Duration (long (or (:duration runner-result) 0)))))

(defn- write-feature-json! [path feature]
  (gherkin/write-json! path feature))

(defn- process-builder [command]
  (doto (ProcessBuilder. ^java.util.List command)
    (.redirectError ProcessBuilder$Redirect/INHERIT)))

(defn- start-worker [command]
  (let [process (.start (process-builder command))]
    {:process process
     :lock (Object.)
     :reader (BufferedReader. (InputStreamReader. (.getInputStream process) StandardCharsets/UTF_8))
     :writer (BufferedWriter. (OutputStreamWriter. (.getOutputStream process) StandardCharsets/UTF_8))}))

(defn- close-worker! [{:keys [process writer]}]
  (try (.close writer) (catch Exception _))
  (try (.waitFor process 100 TimeUnit/MILLISECONDS) (catch Exception _))
  (when (.isAlive process)
    (.destroy process)))

(defn- run-worker-job [worker job]
  (locking (:lock worker)
    (let [started (System/nanoTime)
          request (array-map :id (get-in job [:mutation :ID])
                             :feature_json (:feature-json job)
                             :generated_dir (:generated-dir job)
                             :work_dir (:work-dir job))]
      (try
        (.write (:writer worker) (json/generate-string request))
        (.newLine (:writer worker))
        (.flush (:writer worker))
        (parse-worker-response (.readLine (:reader worker)) request started)
        (catch Exception e
          {:outcome "infrastructure_error"
           :error (.getMessage e)
           :duration (- (System/nanoTime) started)})))))

(defn- parse-worker-response [line request started]
  (if line
    (validated-worker-response (json/parse-string line true) request started)
    {:outcome "infrastructure_error"
     :error "worker exited without response"
     :duration (- (System/nanoTime) started)}))

(defn- validated-worker-response [response request started]
  (let [duration (long (or (:duration response)
                           (- (System/nanoTime) started)))]
    (if (= (:id response) (:id request))
      {:outcome (:outcome response)
       :output (or (:output response) "")
       :error (or (:error response) "")
       :duration duration}
      {:outcome "infrastructure_error"
       :error (format "worker response id %s does not match request id %s" (pr-str (:id response)) (pr-str (:id request)))
       :duration duration})))

(defn- empty-summary []
  (array-map :Total 0 :Killed 0 :Survived 0 :Errors 0))

(defn- increment-summary [summary status]
  (case status
    "killed" (update summary :Killed inc)
    "survived" (update summary :Survived inc)
    "error" (update summary :Errors inc)
    summary))

(defn- completed [summary]
  (+ (:Killed summary 0) (:Survived summary 0) (:Errors summary 0)))

(defn- status-line [started summary running skipped-scenarios skipped-mutations]
  (let [elapsed-ms (quot (- (System/nanoTime) started) 1000000)]
    (str "status elapsed=" elapsed-ms "ms"
         " total=" (:Total summary)
         " completed=" (completed summary)
         " running=" running
         " killed=" (:Killed summary)
         " survived=" (:Survived summary)
         " errors=" (:Errors summary)
         (when (or (pos? skipped-scenarios) (pos? skipped-mutations))
           (str " skipped_scenarios=" skipped-scenarios
                " skipped_mutations=" skipped-mutations)))))

(defn- sha256 [s]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256") (utf8-bytes s))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) digest))))

(defn- strip-mutation-metadata [content]
  (let [state (reduce strip-metadata-line
                      {:in-manifest false :lines []}
                      (str/split content #"\n" -1))]
    (str/replace (str/join "\n" (:lines state)) #"^\n+" "")))

(def metadata-markers
  {"# acceptance-mutation-manifest-begin" :manifest-begin
   "# acceptance-mutation-manifest-end" :manifest-end})

(defn- metadata-marker [trimmed]
  (or (metadata-markers trimmed)
      (when (str/starts-with? trimmed "# mutation-stamp:") :stamp)))

(def boundary-line-handlers
  {:manifest-begin (fn [state _] (assoc state :in-manifest true))
   :manifest-end (fn [state _] (assoc state :in-manifest false))})

(defn- handle-metadata-line [handlers fallback state line]
  (let [trimmed (str/trim line)]
    (if-let [handler (handlers (metadata-marker trimmed))]
      (handler state trimmed)
      (fallback state line trimmed))))

(def strip-line-handlers
  (assoc boundary-line-handlers :stamp (fn [state _] state)))

(defn- keep-source-metadata-line [{:keys [in-manifest] :as state} line _]
  (if in-manifest state (update state :lines conj line)))

(defn- strip-metadata-line [state line]
  (handle-metadata-line strip-line-handlers keep-source-metadata-line state line))

(defn- read-mutation-metadata [feature-path]
  (try
    (let [content (slurp feature-path)
          lines (str/split content #"\n")
          parsed (reduce parse-metadata-line
                         {:in-manifest false :manifest-lines [] :stamp ""}
                         lines)]
      (if (seq (:manifest-lines parsed))
        {:stamp (:stamp parsed)
         :manifest (json/parse-string (str/join "" (:manifest-lines parsed)) true)}
        (when (seq (:stamp parsed))
          {:stamp (:stamp parsed) :manifest {}})))
    (catch Exception _ nil)))

(def parse-line-handlers
  (assoc boundary-line-handlers
         :stamp (fn [state trimmed] (assoc state :stamp (str/replace trimmed #"^# mutation-stamp: sha256=" "")))))

(defn- keep-manifest-metadata-line [{:keys [in-manifest] :as state} _ trimmed]
  (if in-manifest
    (update state :manifest-lines conj (str/trim (str/replace trimmed #"^#" "")))
    state))

(defn- parse-metadata-line [state line]
  (handle-metadata-line parse-line-handlers keep-manifest-metadata-line state line))

(defn- canonical-hash-value [value]
  (let [value (aps-json/strip-empty-keys #{:background :parameters} value)]
    (cond
      (map? value)
      (let [entries (if (every? string? (keys value))
                      (sort-by key value)
                      value)]
        (into (array-map)
              (map (fn [[k v]] [k (canonical-hash-value v)]))
              entries))

      (vector? value)
      (mapv canonical-hash-value value)

      (sequential? value)
      (mapv canonical-hash-value value)

      :else value)))

(defn- hash-json [value]
  (-> (json/generate-string (canonical-hash-value value))
      (str/replace "&" "\\u0026")
      (str/replace "<" "\\u003c")
      (str/replace ">" "\\u003e")
      sha256))

(defn- mutation-count-for-scenario [mutations scenario-index]
  (count (filter #(= scenario-index (:scenario %)) mutations)))

(defn- scenario-index-from-path [path]
  (when-let [[_ index] (re-find #"^\$\.scenarios\[(\d+)\]" path)]
    (Long/parseLong index)))

(defn- scenario-summaries [feature report]
  (if (and (empty? (:results report))
           (= 1 (count (:scenarios feature)))
           (pos? (get-in report [:summary :Total] 0)))
    {0 (:summary report)}
    (reduce (fn [summaries result]
              (if-let [scenario-index (scenario-index-from-path (get-in result [:Mutation :Path]))]
                (-> summaries
                    (update scenario-index (fnil update (empty-summary)) :Total inc)
                    (update scenario-index increment-summary (:Status result)))
                summaries))
            {}
            (:results report))))

(defn- new-manifest [feature-path feature report implementation-hash]
  (let [now (str (Instant/now))
        all-mutations (discover feature)
        summaries (scenario-summaries feature report)]
    (array-map
     :version 1
     :tested_at now
     :feature_name (:name feature)
     :feature_path feature-path
     :background_hash (hash-json (:background feature))
     :implementation_hash implementation-hash
     :scenarios
     (vec
      (keep-indexed
       (fn [i scenario]
         (let [summary (summaries i)]
           (when (and summary (zero? (:Survived summary 0)) (zero? (:Errors summary 0)))
             (array-map :index i
                        :name (:name scenario)
                        :scenario_hash (hash-json scenario)
                        :mutation_count (mutation-count-for-scenario all-mutations i)
                        :result summary
                        :tested_at now))))
       (:scenarios feature))))))

(defn- manifest-entry-reusable? [old current entry level feature mutations]
  (and (= 1 (:version old))
       (= (:feature_name old) (:feature_name current))
       (= (:feature_path old) (:feature_path current))
       (= (:background_hash old) (:background_hash current))
	       (or (not= level "hard") (= (:implementation_hash old) (:implementation_hash current)))
	       (<= 0 (:index entry))
	       (let [scenario (get (:scenarios feature) (:index entry))]
	         (and (some? scenario)
	              (= (:name entry) (:name scenario))
	              (= (:scenario_hash entry) (hash-json scenario))))
       (zero? (get-in entry [:result :Survived] 0))
       (zero? (get-in entry [:result :Errors] 0))
       (= (:mutation_count entry) (mutation-count-for-scenario mutations (:index entry)))))

(defn- merge-reusable-previous-scenarios [current previous feature level]
  (let [existing (set (map :index (:scenarios current)))
        mutations (discover feature)
        reusable (remove #(existing (:index %))
                         (filter #(manifest-entry-reusable? previous current % level feature mutations)
                                 (:scenarios previous)))]
    (update current :scenarios into reusable)))

(defn write-mutation-metadata! [feature-path feature report implementation-hash level write-stamp?]
  (let [content (slurp feature-path)
        previous (read-mutation-metadata feature-path)
        cleaned (strip-mutation-metadata content)
        stamp (sha256 cleaned)
        manifest (cond-> (new-manifest feature-path feature report implementation-hash)
                   previous (merge-reusable-previous-scenarios (:manifest previous) feature level))
        manifest-json (json/generate-string manifest)
        metadata (str (when write-stamp?
                        (str "# mutation-stamp: sha256=" stamp "\n"))
                      "# acceptance-mutation-manifest-begin\n"
                      "# " manifest-json "\n"
                      "# acceptance-mutation-manifest-end\n\n"
                      (str/replace cleaned #"^\n+" ""))]
    (spit feature-path metadata)))

(defn- feature-stamp-valid? [feature-path]
  (when-let [metadata (read-mutation-metadata feature-path)]
    (and (seq (:stamp metadata))
         (= (:stamp metadata) (sha256 (strip-mutation-metadata (slurp feature-path)))))))

(defn- full-skip? [cfg]
  (or (= (:level cfg) "full") (str/blank? (:feature-path cfg))))

(defn- whole-feature-skip [cfg metadata]
  (when (and (empty? (get-in metadata [:manifest :scenarios]))
             (feature-stamp-valid? (:feature-path cfg)))
    (set (range (count (get-in cfg [:feature :scenarios]))))))

(defn- reusable-scenario-skips [cfg metadata mutations]
  (let [current (new-manifest (:feature-path cfg) (:feature cfg) {:summary (empty-summary) :results []}
                              (:implementation-hash cfg))]
    (set (map :index
              (filter #(manifest-entry-reusable? (:manifest metadata) current % (:level cfg) (:feature cfg) mutations)
                      (get-in metadata [:manifest :scenarios]))))))

(defn- accepted-skips [cfg mutations]
  (if (full-skip? cfg)
    #{}
    (if-let [metadata (read-mutation-metadata (:feature-path cfg))]
      (or (whole-feature-skip cfg metadata)
          (reusable-scenario-skips cfg metadata mutations))
      #{})))

(defn- skipped-summary-text [summary]
  (when (or (pos? (:SkippedScenarios summary 0))
            (pos? (:SkippedMutations summary 0)))
    (str " skipped_scenarios=" (:SkippedScenarios summary 0)
         " skipped_mutations=" (:SkippedMutations summary 0))))

(defn- report-header [summary]
  (str "total=" (:Total summary)
       " killed=" (:Killed summary)
       " survived=" (:Survived summary)
       " errors=" (:Errors summary)
       (skipped-summary-text summary)))

(defn- result-detail-text [result]
  (when (#{"survived" "error"} (:Status result))
    (str (when (seq (:Error result))
           (str "  error: " (:Error result) "\n"))
         (when (seq (:Output result))
           (str "  output:\n" (:Output result) "\n")))))

(defn- result-line [result]
  (str (format "%-8s %s\n" (:Status result) (get-in result [:Mutation :Description]))
       (result-detail-text result)))

(defn- report-text [report]
  (str (report-header (:summary report)) "\n"
       (apply str (map result-line (:results report)))))

(defn write-text-report! [report]
  (print (report-text report)))

(defn write-json-report! [report]
  (aps-json/write-pretty-out! (aps-json/strip-empty-keys #{:SkippedScenarios :SkippedMutations} report)))

(defn- slug-char [ch]
  (let [c (Character/toLowerCase ^char ch)]
    (when (or (<= (int \a) (int c) (int \z))
              (<= (int \0) (int c) (int \9)))
      c)))

(defn- append-slug-char [{:keys [s hyphen?] :as state} ch]
  (if-let [c (slug-char ch)]
    {:s (str s c) :hyphen? false}
    (if (and (not hyphen?) (seq s))
      {:s (str s "-") :hyphen? true}
      state)))

(defn- feature-metadata-slug [feature-path]
  (-> (reduce append-slug-char {:s "" :hyphen? false} feature-path)
      :s
      (str/replace #"^-+|-+$" "")))

(defn resolve-implementation-hash [generated-dir feature-path override]
  (if (seq override)
    override
    (metadata-implementation-hash generated-dir feature-path)))

(defn- metadata-implementation-hash [generated-dir feature-path]
  (try
    (let [metadata (aps-json/read-json-file (metadata-path generated-dir feature-path))]
      (if (= (:feature_path metadata) feature-path)
        (or (:implementation_hash metadata) "unknown")
        "unknown"))
    (catch Exception _ "unknown")))

(defn- metadata-path [generated-dir feature-path]
  (str (io/file generated-dir "metadata" (str (feature-metadata-slug feature-path) ".json"))))

(defn- executable-mutation-indexes [mutations skip]
  (vec (keep-indexed (fn [i mutation] (when-not (skip (:scenario mutation)) i)) mutations)))

(defn- final-summary [summary skipped-scenarios skipped-mutations]
  (cond-> summary
    (pos? skipped-scenarios) (assoc :SkippedScenarios skipped-scenarios)
    (pos? skipped-mutations) (assoc :SkippedMutations skipped-mutations)))

(defn- emit-status [cfg started summary running skipped-scenarios skipped-mutations]
  (when (pos? (:status-interval-ms cfg))
    (binding [*out* *err*]
      (println (status-line started summary running skipped-scenarios skipped-mutations)))))

(defn- run-one-mutation! [cfg generated-dir worker mutation result-index results summary running]
  (let [mutation-work-dir (str (io/file (:work-dir cfg) "mutations" (:ID mutation)))
        feature-json (str (io/file mutation-work-dir "feature.json"))]
    (swap! running inc)
    (try
      (write-feature-json! feature-json (apply-mutation (:feature cfg) mutation))
      (let [runner-result (run-worker-job worker {:mutation mutation
                                                  :feature-json feature-json
                                                  :generated-dir generated-dir
                                                  :work-dir mutation-work-dir})
            result (make-result mutation runner-result)]
        (swap! results assoc result-index result)
        (swap! summary increment-summary (:Status result)))
      (catch Exception e
        (let [result (make-result mutation {:outcome "infrastructure_error"
                                            :error (.getMessage e)})]
          (swap! results assoc result-index result)
          (swap! summary increment-summary "error")))
      (finally
        (swap! running dec)))))

(defn- submit-mutation! [executor cfg generated-dir workers mutations results summary running result-index mutation-index]
  (.submit executor
           (reify Callable
             (call [_]
               (run-one-mutation! cfg generated-dir
                                  (workers (mod result-index (count workers)))
                                  (mutations mutation-index)
                                  result-index results summary running)))))

(defn- execute-mutations! [executor cfg generated-dir workers mutations executable-indexes results summary running]
  (let [futures (doall
                 (map-indexed
                  #(submit-mutation! executor cfg generated-dir workers mutations results summary running %1 %2)
                  executable-indexes))]
    (doseq [f futures] (.get f))))

(defn- run-config [cfg]
  (merge {:workers 1
          :work-dir "build/acceptance-mutation"
          :level "hard"
          :generated-dir nil
          :status-interval-ms 30000}
         cfg))

(defn run [cfg]
  (let [cfg (run-config cfg)
        generated-dir (or (:generated-dir cfg) (str (io/file (:work-dir cfg) "generated")))
        mutations (discover (:feature cfg))
        skip (accepted-skips cfg mutations)
        executable-indexes (executable-mutation-indexes mutations skip)
        skipped-scenarios (count skip)
        skipped-mutations (- (count mutations) (count executable-indexes))
        summary0 (assoc (empty-summary) :Total (count executable-indexes))
        results (atom (vec (repeat (count executable-indexes) nil)))
        summary (atom summary0)
        running (atom 0)
        started (System/nanoTime)
        workers (mapv start-worker (repeat (max 1 (:workers cfg)) (:runner-command cfg)))
        executor (Executors/newFixedThreadPool (max 1 (:workers cfg)))]
    (try
      (write-feature-json! (str (io/file (:work-dir cfg) "base" "feature.json")) (:feature cfg))
      (emit-status cfg started @summary @running skipped-scenarios skipped-mutations)
      (execute-mutations! executor cfg generated-dir workers mutations executable-indexes results summary running)
      (let [final-summary (final-summary @summary skipped-scenarios skipped-mutations)
            report (array-map :summary final-summary :results (vec (remove nil? @results)))]
        (emit-status cfg started final-summary @running skipped-scenarios skipped-mutations)
        report)
      (finally
        (.shutdown executor)
        (doseq [worker workers] (close-worker! worker))))))
