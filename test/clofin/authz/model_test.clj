(ns clofin.authz.model-test
  "The permission model, tested as a value.

  Two of these tests are about what the model *does not* contain — there is no
  superuser, and no role holds every permission. A property stated only in a
  docstring survives exactly as long as the reviewer who remembers it, and
  \"give this role everything, it is easier\" is the single most likely way C-08
  stops being true."
  (:require [clofin.authz.model :as model]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- actor
  [& {:keys [roles status] :or {roles #{} status :active}}]
  {:id (random-uuid) :organisation-id (random-uuid)
   :display-name "Test actor" :status status :roles roles :limits {}})

;; ---------------------------------------------------------------------------
;; Default deny
;; ---------------------------------------------------------------------------

(deftest an-actor-with-no-roles-can-do-nothing
  (testing "C-08: an absent permission is a denied permission"
    (let [nobody (actor)]
      (is (empty? (model/granted nobody)))
      (doseq [permission model/permissions]
        (is (false? (model/permitted? nobody permission))
            (str "an actor with no roles must not hold " permission))))))

(deftest an-unknown-permission-is-denied-like-any-other
  (is (false? (model/permitted? (actor :roles #{:controller}) :payment/detonate))))

(deftest authorise!-refuses-a-permission-the-actor-lacks
  (let [operator (actor :roles #{:operator})
        t (try (model/authorise! operator :payment/approve) nil (catch Exception e e))]
    (is (some? t))
    (is (= :forbidden (:clofin/error (ex-data t))))
    (testing "the missing permission is named; the held ones are not"
      (is (= "payment/approve" (:permission (ex-data t))))
      (is (not (str/includes? (pr-str (ex-data t)) "payment/create"))
          "telling a refused caller what it *can* do turns a refusal into a capability listing"))))

(deftest authorise!-refuses-a-permission-that-does-not-exist
  (testing "a typo in a handler must not read as a correctly refused request"
    (let [t (try (model/authorise! (actor :roles #{:operator}) :payment/aprove)
                 nil (catch Exception e e))]
      (is (= :validation (:clofin/error (ex-data t)))))))

(deftest authorise!-returns-the-actor-when-permitted
  (let [approver (actor :roles #{:approver})]
    (is (= approver (model/authorise! approver :payment/approve)))))

;; ---------------------------------------------------------------------------
;; Suspension
;; ---------------------------------------------------------------------------

(deftest a-suspended-actor-holds-nothing-whatever-their-roles
  (testing "suspension is a complete stop, not a flag a caller may weigh"
    (let [suspended (actor :roles (set model/roles) :status :suspended)]
      (is (empty? (model/granted suspended)))
      (doseq [permission model/permissions]
        (is (false? (model/permitted? suspended permission))))
      (is (false? (model/approver? suspended))))))

(deftest a-014-only-active-grants-anything-across-the-whole-status-vocabulary
  (testing "stated over `actor-statuses` rather than over the one status someone
            sampled — the set is compared with `actor_status_known` in
            `clofin.db.vocabulary-test`, so this covers whatever it holds"
    (doseq [status model/actor-statuses
            :when (not= :active status)]
      (is (empty? (model/granted (actor :roles (set model/roles) :status status)))
          (str status " must grant nothing"))))
  (testing "and a status neither the model nor the schema knows grants nothing either"
    (is (empty? (model/granted (actor :roles (set model/roles) :status :undead)))
        "`granted` fails closed for an unrecognised status, which is what makes a
         deployment mid-migration safe rather than merely unlikely")))

;; ---------------------------------------------------------------------------
;; No superuser
;; ---------------------------------------------------------------------------

(deftest no-role-holds-every-permission
  (testing "C-08: there is no superuser in the model, and none may be added"
    (doseq [[role granted] model/role-permissions]
      (is (seq (set/difference (set model/permissions) granted))
          (str "role " role " holds every permission — that is a superuser, "
               "and default deny means default deny. Grant rights explicitly "
               "in the fixture that needs them instead.")))))

(deftest no-single-role-is-both-maker-and-checker
  (testing "C-01 expressed as a permission set, before any instruction exists"
    (doseq [[role granted] model/role-permissions]
      (is (not (and (contains? granted :payment/create)
                    (contains? granted :payment/approve)))
          (str "role " role " can both raise and approve a payment — "
               "segregation of duties cannot then depend on who happens to "
               "hold which role.")))))

(deftest no-single-role-both-approves-and-settles
  (testing "C-01, AC-10: approving a payment and pushing it out of the door are the last two
            gates it passes, and one actor holding both is a maker–checker boundary with the
            same person on either side of the final step"
    (doseq [[role granted] model/role-permissions]
      (is (not (and (contains? granted :payment/approve)
                    (contains? granted :settlement/execute)))
          (str "role " role " can both approve a payment and settle it — the approval it "
               "gave would be the only check on money it then released.")))))

(deftest settlement-is-a-controller-right-and-only-a-controller-right
  (testing "stated as a value so a grant added elsewhere is visible here rather than in an audit"
    (is (= #{:controller}
           (set (keep (fn [[role granted]]
                        (when (contains? granted :settlement/execute) role))
                      model/role-permissions))))))

(deftest reconciliation-execute-is-a-controller-right-and-never-an-approvers
  (testing "an actor who could propose an adjustment and then approve it would be
            a maker-checker boundary with one person on both sides (C-01). The
            permission split is the belt; `clofin.authz.approval/evaluate`
            refusing :self-approval per adjustment is the brace"
    (is (= #{:controller}
           (set (keep (fn [[role granted]]
                        (when (contains? granted :reconciliation/execute) role))
                      model/role-permissions))))
    (doseq [[role granted] model/role-permissions]
      (is (not (and (contains? granted :reconciliation/execute)
                    (contains? granted :payment/approve)))
          (str role " both proposes reconciliation adjustments and approves")))))

(deftest an-approver-can-read-a-reconciliation-break-and-cannot-open-one
  (testing "a checker who cannot read the adjustment they are being asked to
            approve is a rubber stamp, which is the control failure the PRD
            opens with — and reading is not proposing"
    (let [granted (:approver model/role-permissions)]
      (is (contains? granted :reconciliation/read))
      (is (not (contains? granted :reconciliation/execute))))))

(deftest the-auditor-role-is-read-only
  (testing "an auditor who can change the thing being audited is not an auditor"
    ;; Stated as a rule over the permission *name* rather than as a list of the
    ;; reads that existed when this was written. A hard-coded allowlist has to
    ;; be extended every time a read is added — and the extension is exactly
    ;; where someone waves a write through, because the diff looks like the four
    ;; that came before it. `:organisation/read` (A-006) was the first such
    ;; addition and is what surfaced the shape.
    (doseq [permission (:auditor model/role-permissions)]
      (is (= "read" (name permission))
          (str "auditor holds " permission ", which is not a read")))))

;; ---------------------------------------------------------------------------
;; The model and the schema cannot disagree
;; ---------------------------------------------------------------------------

(deftest every-role-in-the-model-is-a-role-the-migration-text-mentions
  ;; **This is the weaker half of the guard, and it says so.** It read
  ;;
  ;;   #"'(operator|approver|controller|compliance|auditor)'"
  ;;
  ;; and compared the result with `model/roles` — a regex that can only ever
  ;; capture the five roles it already names, so a sixth SQL role matched
  ;; nothing, `declared` still held five, and the equality still passed. It was
  ;; described as *the* role drift guard, and it was blind in the one direction
  ;; drift actually travels (audit finding **A-014**).
  ;;
  ;; What survives here is what a unit test can honestly assert without a
  ;; database: every role the model declares is *named somewhere* in the
  ;; migration that constrains the column. Set equality against the live
  ;; `role_known` constraint — in both directions, over values discovered
  ;; rather than enumerated — is `clofin.db.vocabulary-test`.
  (testing "a role here and not in the check constraint fails on insert, in production"
    (let [sql (slurp (io/file "resources/migrations/0005-authorisation-and-audit.sql"))]
      (doseq [role model/roles]
        (is (str/includes? sql (str "'" (name role) "'"))
            (str role " is in `model/roles` and appears nowhere in migration 0005"))))))

(deftest every-role-has-a-permission-set
  (testing "a role nobody wrote permissions for grants nothing, silently"
    (is (= (set model/roles) (set (keys model/role-permissions))))))

(deftest every-granted-permission-is-a-known-permission
  (doseq [[role granted] model/role-permissions
          permission granted]
    (is (contains? model/permissions permission)
        (str "role " role " grants " permission ", which is not in `permissions` — "
             "a typo here grants nothing and looks like a grant"))))

(deftest every-permission-is-reachable-by-some-role
  (testing "a permission no role holds is a permission nobody can ever exercise"
    (let [reachable (reduce set/union #{} (vals model/role-permissions))]
      (is (empty? (set/difference (set model/permissions) reachable))))))
