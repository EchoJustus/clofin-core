(ns clofin.tools.screening-list-args-test
  "The screening-list loader's command line, parsed without a database: a unit
  namespace, so `make test` and `make verify` run it (017-REQ R-11, and the
  re-review's note that it first lived in a database-fixtured namespace)."
  (:require [clofin.tools.screening-list :as tool]
            [clojure.test :refer [deftest is testing]]))

(deftest a-malformed-command-line-is-a-usage-error-not-a-stack-trace
  (testing "017-REQ R-11: `load f --replacing` with no version reached
            `apply hash-map` with an odd count, which threw before `-main`'s
            `try`. Every malformed line parses to nil, which `-main` answers
            with its usage and exit 2 — including the ones an even count let
            through: a flag given twice, whose first value `hash-map` silently
            dropped, a flag where the file belongs, and a flag where the
            version belongs"
    (let [parse #'tool/parse-args]
      (doseq [args [["load" "f.edn" "--replacing"]
                    ["load" "f.edn" "--replacing" "v1" "--replacing"]
                    ["load" "f.edn" "--replacing" "v1" "--replacing" "v2"]
                    ["load" "--replacing"]
                    ["load" "--replacing" "v1"]
                    ["load" "f.edn" "--replacing" "--replacing"]
                    ["load" "f.edn" "--replacing" "--other"]
                    ["load" "f.edn" "--other" "x"]
                    ["load"]
                    ["retire"]
                    ["retire" "--replacing"]
                    ["retire" "v1" "extra"]
                    ["unload" "v1"]
                    []]]
        (is (nil? (parse args)) (pr-str args)))
      (testing "negative control: the well-formed lines parse"
        (is (= {:command :load :path "f.edn" :replacing "v1"}
               (parse ["load" "f.edn" "--replacing" "v1"])))
        (is (= {:command :load :path "f.edn" :replacing nil} (parse ["load" "f.edn"])))
        (is (= {:command :retire :version "v1"} (parse ["retire" "v1"])))))))

(deftest the-loader-says-refused-only-for-its-own-refusals
  (testing "017-REQ §8: `Refused:` is the tool refusing a list act; anything
            else — a configuration that does not load, which also throws an
            ExceptionInfo — is `Failed:`"
    (let [line #'tool/outcome-line]
      (is (= "Refused: no such version"
             (line (ex-info "no such version" {:reason "unknown-version"}))))
      (is (= "Refused: bad rule"
             (line (ex-info "bad rule" {:reason "unsupported-operator"})))
          "a list-shape refusal of clofin.screening.list is the tool's too")
      (is (= "Failed: CLOFIN_ENV must be one of dev, prod, test"
             (line (ex-info "CLOFIN_ENV must be one of dev, prod, test" {:clofin/error :invalid})))))))
