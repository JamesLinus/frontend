(ns circle.backend.action.bash
  (:use [arohner.utils :only (inspect)])
  (:require pallet.action-plan)
  (:use [circle.util.core :only (apply-map apply-if)])
  (:use [circle.util.predicates :only (named?)])
  (:use [circle.backend.build :only (*pwd* *env* log-ns build-log build-log-error)])
  (:require [circle.backend.ssh :as ssh])
  (:require [circle.backend.ec2 :as ec2])
  (:require [circle.backend.action :as action]))

(defmacro quasiquote [& forms]
  `(pallet.stevedore/quasiquote ~forms))

(defn format-bash-cmd [body]
  (let [cd-form (when (seq *pwd*)
                  (quasiquote (cd ~*pwd*)))
        env-form (map (fn [[k v]]
                        (format "export %s=%s" k (apply-if (named? v) name v))) *env*)]
    (concat cd-form env-form body)))

(defn emit-form [body]
  (let [body (if (string? body)
               [body]
               body)
        body (format-bash-cmd body)]
    (pallet.stevedore/with-script-language :pallet.stevedore.bash/bash
      (pallet.stevedore/with-line-number [*file* (:line (meta body))]
        (binding [pallet.stevedore/*script-ns* *ns*]
          (pallet.stevedore/emit-script body))))))

(defn remote-bash
  "Execute bash code on the remote server.

   ssh-map is a map containing the keys :username, :public-key, :private-key :ip-addr. All keys are required."
  [ssh-map body & {}]
  (let [cmd (emit-form body)
        result (ssh/remote-exec ssh-map cmd)]
    result))

(defn ssh-map-for-build [build]
  (let [instance-id (-> @build :instance-ids (first))
        ip-addr (ec2/public-ip instance-id)]
    (merge (-> @build :group :circle-node-spec) {:ip-addr ip-addr})))

(defn remote-bash-build
  [build body]
  (remote-bash (ssh-map-for-build build) body))

(defn bash
  "Returns a new action that executes bash on the host. Body is a string"
  [body & {:keys [name abort-on-nonzero]
           :or {abort-on-nonzero true}
           :as opts}]
  (let [name (or name (str body))]
    (action/action :name name
                   :act-fn (fn [build]
                             (binding [ssh/handle-out build-log
                                       ssh/handle-err build-log-error]
                               (let [result (remote-bash-build build body)]
                                 (when (and (not= 0 (-> result :exit)) abort-on-nonzero)
                                   (action/abort! build (str body " returned exit code " (-> result :exit))))
                                 (action/add-action-result result)
                                 result))))))

