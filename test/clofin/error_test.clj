(ns clofin.error-test
  (:require [clofin.error :as err]
            [clojure.test :refer [deftest is testing]]))

(deftest every-error-type-has-an-externally-visible-meaning
  (testing "a category without a status and title cannot be rendered to a caller"
    (doseq [[type {:keys [status title]}] err/error-types]
      (is (integer? status) (str type " needs a status"))
      (is (<= 400 status 599) (str type " must map to an error status"))
      (is (string? title) (str type " needs a title")))))

(deftest domain-errors-are-distinguishable-from-defects
  (testing "a domain error is a modelled outcome"
    (is (err/domain-error? (err/error :validation "bad input"))))

  (testing "an arbitrary exception is a defect, and must not be rendered as a domain outcome"
    (is (not (err/domain-error? (ex-info "boom" {}))))
    (is (not (err/domain-error? (RuntimeException. "boom"))))
    (is (not (err/domain-error? (ex-info "boom" {:clofin/error :not-a-real-category}))))))

(deftest error-data-travels-with-the-error
  (let [t (try (err/invalid! "Currency is not supported" {:currency "XYZ"})
               (catch clojure.lang.ExceptionInfo e e))]
    (is (= :validation (:clofin/error (ex-data t))))
    (is (= "XYZ" (:currency (ex-data t))))
    (is (= "Currency is not supported" (ex-message t)))))

(deftest throwing-helpers-carry-the-right-category
  (is (= :not-found (:clofin/error (ex-data (try (err/not-found! "gone") (catch Exception e e))))))
  (is (= :conflict  (:clofin/error (ex-data (try (err/conflict! "clash") (catch Exception e e))))))
  (is (= :forbidden (:clofin/error (ex-data (try (err/forbidden! "no") (catch Exception e e)))))))

(deftest an-unknown-category-cannot-be-constructed
  (testing "categories are a closed set, so the HTTP layer can always render one"
    (is (thrown? AssertionError (err/error :teapot "I'm a teapot")))))
