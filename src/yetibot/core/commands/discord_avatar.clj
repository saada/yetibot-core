(ns yetibot.core.commands.discord-avatar
  "Resolve a Discord user's profile image (avatar) URL by username."
  (:require [yetibot.core.hooks :refer [cmd-hook]]
            [yetibot.core.chat :refer [*adapter*]]
            [yetibot.core.models.users :as users]
            [taoensso.timbre :refer [info debug]]))

(def ^:private cdn-base "https://cdn.discordapp.com")

(defn- avatar-url
  "Build the Discord CDN avatar URL for a user. Falls back to the default
   avatar if the user has no custom avatar set."
  [{:keys [id avatar discriminator]}]
  (if avatar
    (let [ext (if (.startsWith ^String avatar "a_") "gif" "png")]
      (format "%s/avatars/%s/%s.%s?size=512" cdn-base id avatar ext))
    ;; Default avatar: index is discriminator mod 5 (or (id >> 22) mod 6 for
    ;; new username system where discriminator is 0)
    (let [disc (if (string? discriminator)
                 (parse-long discriminator)
                 (or discriminator 0))
          index (if (zero? disc)
                  (mod (bit-shift-right (parse-long (str id)) 22) 6)
                  (mod disc 5))]
      (format "%s/embed/avatars/%d.png" cdn-base index))))

(defn discord-avatar-cmd
  "discord-avatar <username> # get the Discord avatar URL for a user

   Examples:
   discord-avatar benson
   discord-avatar devops-dave"
  {:yb/cat #{:img}}
  [{:keys [args chat-source]}]
  (let [username (clojure.string/trim args)]
    (if-let [user (users/find-user-like chat-source username)]
      (let [url (avatar-url user)]
        (info "discord-avatar: resolved" username "to" url)
        {:result/value url
         :result/data {:username (:username user) :url url}})
      {:result/error
       (str "Could not find a user matching \"" username
            "\". They may need to send a message first so I can learn about them.")})))

(cmd-hook #"discord-avatar"
  #".+" discord-avatar-cmd)
