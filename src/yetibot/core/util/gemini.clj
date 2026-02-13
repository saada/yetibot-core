(ns yetibot.core.util.gemini
  "Shared utilities for interacting with the Google Gemini API."
  (:require [clj-http.client :as client]
            [clojure.data.json :as json]
            [clojure.spec.alpha :as s]
            [taoensso.timbre :refer [info error]]
            [yetibot.core.config :refer [get-config]]))

(s/def ::key string?)

(s/def ::gemini-config (s/keys :req-un [::key]))

(def default-model "gemini-2.0-flash-exp")

(defn config
  "Returns the Gemini API config map, or nil if not configured.
   Evaluated at call time to avoid load-order issues."
  []
  (:value (get-config ::gemini-config [:gemini :api])))

(defn configured? []
  (some? (config)))

(defn- gemini-model []
  (or (:model (config)) default-model))

(defn- extract-image
  "Extract the first image part from the Gemini API response."
  [response-body]
  (let [parts (get-in response-body [:candidates 0 :content :parts])]
    (some (fn [part]
            (when-let [inline-data (:inlineData part)]
              {:data (:data inline-data)
               :mime-type (:mimeType inline-data)}))
          parts)))

(def ^:private error-messages
  {429 "Gemini API rate limit exceeded. Please wait a moment and try again."
   400 "Gemini API rejected the request. The prompt may be invalid or unsupported."
   401 "Gemini API key is invalid. Check your `gemini.api.key` config."
   403 "Gemini API access denied. Your API key may lack image generation permissions."})

(defn- parse-error-message
  "Extract a human-readable error message from a Gemini API error response."
  [{:keys [status body]}]
  (let [detail (try
                 (let [parsed (if (string? body) (json/read-str body) body)]
                   (or (get-in parsed ["error" "message"])
                       (get-in parsed [:error :message])))
                 (catch Exception _ nil))
        default-msg (get error-messages status
                         (str "Gemini API error (HTTP " status ")"))]
    (if detail
      (str default-msg " Details: " detail)
      default-msg)))

(defn generate-image
  "Call the Gemini API to generate an image from a text prompt.
   Accepts an optional system-instruction string for guiding generation style."
  ([prompt] (generate-image prompt nil))
  ([prompt system-instruction]
   (let [api-key (:key (config))
         model (gemini-model)
         url (format
              "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s"
              model api-key)
         body (cond-> {:contents [{:parts [{:text prompt}]}]
                       :generationConfig {:responseModalities ["TEXT" "IMAGE"]}}
                system-instruction
                (assoc :systemInstruction
                       {:parts [{:text system-instruction}]}))
         {:keys [status] :as response}
         (client/post url
                      {:content-type :json
                       :body (json/write-str body)
                       :as :json
                       :throw-exceptions false})]
     (if (<= 200 status 299)
       (extract-image (:body response))
       (throw (ex-info (parse-error-message response)
                       {:status status :prompt prompt}))))))

(defn yetibot-base-url []
  (or (:value (get-config string? [:url]))
      (:value (get-config string? [:endpoint]))
      "http://localhost:3003"))
