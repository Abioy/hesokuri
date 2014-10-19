; Copyright (C) 2013 Google Inc.
;
; Licensed under the Apache License, Version 2.0 (the "License");
; you may not use this file except in compliance with the License.
; You may obtain a copy of the License at
;
;    http://www.apache.org/licenses/LICENSE-2.0
;
; Unless required by applicable law or agreed to in writing, software
; distributed under the License is distributed on an "AS IS" BASIS,
; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
; See the License for the specific language governing permissions and
; limitations under the License.

(ns hesokuri.source
  "Implementation of the source object. A valid source object has the following
  required fields:
  repo - The repo object for accessing the repo on disk.
  source-def - the definition of this source (see hesokuri.source-def).
  peers - a map of hostnames to the corresponding peer object.
  local-identity - the hostname or IP of this system."
  (:use [clojure.java.io :only [file]]
        [clojure.java.shell :only [with-sh-dir sh]]
        [clojure.string :only [trim]]
        hesokuri.util
        hesokuri.watching)
  (:require [hesokuri.branch :as branch]
            [hesokuri.peer :as peer]
            [hesokuri.repo :as repo]
            [hesokuri.source-def :as source-def]))

(defn- refresh
  "Updates values of the source object based on the state of the repo."
  [{:keys [repo] :as self}]
  (into self
   (letmap
    [;; Map of branch names to their hashes. The branches should be
     ;; hesokuri.branch objects.
     branches
     (into {}
      (map (fn [[branch hash]] [(branch/parse-underscored-name branch) hash])
           (repo/branches repo)))

     working-area-clean (repo/working-area-clean repo)

     checked-out-branch
     (let [s (repo/checked-out-branch repo)]
       (and s (branch/parse-underscored-name s)))])))

(defn- advance-bc
  [{:keys [branches repo source-def] :as self}]
  (doseq [branch (keys branches)]
    (cond
     (source-def/unwanted-branch? source-def (:name branch))
     (repo/delete-branch repo (branch/underscored-name branch) :force)

     (and (not (source-def/live-edit-branch? source-def (:name branch)))
          (:peer branch))
     (repo/delete-branch repo (branch/underscored-name branch))))
  self)

(defn- advance-a
  ([self]
     (advance-a self (seq (:branches self))))
  ([{all-branches :branches
     :keys [repo checked-out-branch working-area-clean source-def]
     :as self}
    branches]
     (if branches
       (let [[[branch hash] & branches] branches
             branch-local (branch/of (:name branch))
             local-hash (all-branches branch-local)
             checked-out-branch-local (= branch-local checked-out-branch)]

         (if (and (:peer branch)
                  (source-def/live-edit-branch? source-def (:name branch))
                  (or working-area-clean
                      (not checked-out-branch-local))
                  (or (not local-hash)
                      (repo/fast-forward? repo local-hash hash true)))

           (let [branch (branch/underscored-name branch)
                 branch-local (branch/underscored-name branch-local)]
             (if checked-out-branch-local
               (when (zero? (repo/hard-reset repo branch))
                 (repo/delete-branch repo branch))
               (repo/rename-branch repo branch branch-local :allow-overwrite))
             (let [self (refresh self)]
               (recur self (seq (:branches self)))))

           (recur self branches)))

       self)))

(defn init-repo
  "Initializes the git repo if it does not exist already. Returns the new state
  of the source object."
  [{:keys [repo] :as self}]
  (assoc self :repo (repo/init repo)))

(def advance
  "Checks for local branches that meet the following criteria, and performs
  the given operation, 'advancing' when appropriate.
  a) If some live-edit branch LEB is not checked out, or it is checked out but
     the working area is clean, and some branch LEB_hesokr_* is a fast-forward
     of LEB, then rename the LEB_hesokr_* branch to LEB, and remove the
     existing LEB branch.
  b) For any branch B, where B has been merged in its upstream branch, and B has
     a name (BRANCH)_hesokr_*, and BRANCH is not a live-edit branch, delete
     branch B with 'git branch -d'.
  c) For any branch specified as unwanted in the source-def, delete it with
     'git branch -D'. For instance, if 'foo' is unwanted, then any branch named
     'foo' or 'foo_hesokr_*' will be deleted."
  #(-> % refresh advance-a advance-bc))

(defn- do-push-for-peer
  "Push all branches as necessary to keep a peer up-to-date.
  When pushing:
  * third-party peer branches - which is any branch named *_hesokr_(HOST) where
    HOST is not me or the push destination peer, try to push to the same branch
    name, but if it fails, ignore it.
  * local branch - which is any branch that is not named in the form of
    *_hesokr_*, force push to (BRANCH_NAME)_hesokr_(MY_HOSTNAME)"
  [{:keys [peers branches local-identity repo source-def] :as self}
   peer-host]
  (doseq [:let [peer-path ((source-def/host-to-path source-def) peer-host)]
          :when peer-path
          branch (keys branches)]
    (send-off
     (peers peer-host)
     peer/push
     repo
     (->PeerRepo peer-host peer-path)
     branch
     (branches branch)
     (let [force-args (-> branch
                          (assoc :peer local-identity)
                          branch/underscored-name
                          (cons [:allow-non-ff]))
           normal-args (-> branch
                           branch/underscored-name
                           (cons [(not :allow-non-ff)]))]
       (cond
        (every? #(not= (:peer branch) %) [nil local-identity peer-host])
        [normal-args]

        (not (:peer branch))
        [force-args]

        :else []))))
  self)

(defn push-for-peer
  "Push all branches necessary to keep one peer up-to-date."
  [self peer-host]
  (-> self refresh (do-push-for-peer peer-host)))

(defn push-for-all-peers
  "Pushes all branches necessary to keep all peers up-to-date."
  [{:keys [peers] :as self}]
  (loop [self self
         peer-hosts (keys peers)]
    (cond
     (nil? peer-hosts) self

     :else (recur (push-for-peer self (first peer-hosts))
                  (next peer-hosts)))))

(defn stop-watching
  "Stops watching the file system. If not watching, this is a no-op."
  [{:keys [watcher] :as self}]
  (when watcher ((watcher :stopper)))
  (dissoc self :watcher))

(defn start-watching
  "Registers paths in this source's repo to be notified of changes so it can
  automatically advance and push"
  [{:keys [repo] :as self}]
  (let [self-agent *agent*
        watcher (repo/watch-refs-heads-dir
                 repo
                 (fn []
                   (send self-agent advance)
                   (send self-agent push-for-all-peers)))]
    (-> self stop-watching refresh (assoc :watcher watcher))))
