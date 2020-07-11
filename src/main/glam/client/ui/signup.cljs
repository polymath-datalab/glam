(ns glam.client.ui.signup
  (:require
    [clojure.string :as str]
    [clojure.pprint :refer [pprint]]
    [com.fulcrologic.fulcro.algorithms.denormalize :as fdn]
    [com.fulcrologic.fulcro.algorithms.form-state :as fs]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.data-fetch :as df]
    [com.fulcrologic.fulcro.dom :as dom]
    [com.fulcrologic.fulcro.mutations :as m :refer [defmutation]]
    [com.fulcrologic.fulcro.ui-state-machines :as uism]
    [com.fulcrologic.guardrails.core :refer [>defn => | ?]]
    [dv.fulcro-util :as fu]
    [glam.models.session :as session]
    [glam.client.router :as r]
    [glam.models.session :refer [session-join Session get-session signup-ident signup]]
    [sablono.util :as su]
    [taoensso.timbre :as log]
    [com.fulcrologic.fulcro.components :as c]))

(declare Signup)

(defn clear-signup-form*
  "Mutation helper: Updates state map with a cleared signup form that is configured for form state support."
  [state-map]
  (log/info "Clearing signup form")
  (-> state-map
    (assoc-in signup-ident
      {:account/email          ""
       :account/password       ""
       :account/password-again ""})
    (fs/add-form-config* Signup signup-ident)))

(defmutation clear-signup-form [_]
  (action [{:keys [state]}]
    (swap! state clear-signup-form*)))

(defmutation
  mark-complete!* [{field :field}]
  (action [{:keys [state]}]
    (log/info "Marking complete field: " field)
    (swap! state fs/mark-complete* signup-ident field)))

(defn mark-complete!
  [this field]
  (comp/transact!! this [(mark-complete!* {:field field})]))

(defn signup-valid [form field]
  (let [v (get form field)]
    (case field
      :account/email (fu/valid-email? v)
      :account/password (fu/valid-password? v)
      :account/password-again (= (:account/password form) v))))

(def validator (fs/make-validator signup-valid))

(defsc Signup [this {:account/keys [email password password-again] :as props}]
  {:query             [:account/email :account/password :account/password-again fs/form-config-join session-join
                       [df/marker-table ::signup]]
   :initial-state     (fn [_]
                        (fs/add-form-config Signup
                                            {:account/email          ""
                                             :account/password       ""
                                             :account/password-again ""}))
   :form-fields       #{:account/email :account/password :account/password-again}
   :ident             (fn [] signup-ident)
   :componentDidMount (fn [this] (comp/transact! this [(clear-signup-form {})]))}
  (let [server-err (:session/server-error-msg (get-session props))
        form-valid? (= :valid (validator props))
        submit! (fu/prevent-default
                  #(when form-valid?
                     (comp/transact! this [(signup {:password password :email email})])))
        checked? (fs/checked? props)
        mark-complete! (partial mark-complete! this)
        saving? (df/loading? (get props [df/marker-table ::signup]))]
    [:div
     [:h3 "Signup"]
     [:form
      {:class    (str "ui form" (when checked? " error"))
       :onSubmit submit!}
      ^:inline (fu/ui-email this :account/email email mark-complete! :autofocus? false
                 :tabIndex 1)
      ^:inline (fu/ui-password2 this :account/password password :tabIndex 2)
      ^:inline (fu/ui-verify-password this :account/password-again
                 password password-again mark-complete!
                 :tabIndex 3)
      (when-not (empty? server-err) [:.ui.error.message server-err])
      [:button
       {:type      "submit"
        :tab-index 4
        :class     (str "ui primary button" (when saving? " loading"))
        :disabled  (not form-valid?)} "Sign Up"]]]))

(def ui-signup (c/factory Signup))
