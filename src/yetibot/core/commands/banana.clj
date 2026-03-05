(ns yetibot.core.commands.banana
  (:require [clojure.string :as s]
            [taoensso.timbre :refer [info error]]
            [yetibot.core.hooks :refer [cmd-hook]]
            [yetibot.core.util.gemini :as gemini]
            [yetibot.core.webapp.routes.images :refer [store-image!]]))

(def ^:private url-pattern
  "Regex to find image URLs in prompt text. Matches common image extensions
   as well as known image CDN hosts (Discord CDN, imgflip, imgur, etc.)."
  #"(https?://(?:cdn\.discordapp\.com|i\.imgflip\.com|i\.imgur\.com|media\.discordapp\.net)\S+|https?://\S+\.(?:jpg|jpeg|png|gif|webp)(?:[?\#]\S*)?)")

(defn- extract-image-url
  "Extract the first image URL from the prompt text, returning
   [image-url remaining-text] or nil if no URL found."
  [text]
  (when-let [match (re-find url-pattern text)]
    (let [url (if (string? match) match (first match))
          remaining (s/trim (s/replace text url ""))]
      [url remaining])))

(defn banana-cmd
  "banana <prompt> # generate an image using Gemini image generation.
   If the prompt contains an image URL, the image will be fetched and
   used as input for editing/remixing. This lets you build aliases that
   modify existing images.

   Examples:
   banana a cat wearing a top hat
   banana use this image https://example.com/photo.jpg and add sunglasses"
  {:yb/cat #{:img}}
  [{:keys [match]}]
  (if (gemini/configured?)
    (try
      (if-let [[image-url remaining-text] (extract-image-url match)]
        ;; Image URL detected — use image-input mode
        (let [prompt (if (s/blank? remaining-text)
                       "Edit this image creatively."
                       remaining-text)
              _ (info "banana: generating image with input url:" image-url
                      "prompt:" prompt)
              image (gemini/generate-image-with-input prompt image-url)
              id (store-image! image)
              base-url (gemini/yetibot-base-url)
              result-url (format "%s/generated-images/%s.png" base-url id)]
          (info "banana: image generated successfully, serving at" result-url)
          {:result/value result-url
           :result/data {:id id :prompt match :url result-url}})
        ;; No URL — text-only generation (original behavior)
        (let [_ (info "banana: generating image for prompt:" match)
              image (gemini/generate-image
                     (str "Generate an image: " match))
              id (store-image! image)
              base-url (gemini/yetibot-base-url)
              result-url (format "%s/generated-images/%s.png" base-url id)]
          (info "banana: image generated successfully, serving at" result-url)
          {:result/value result-url
           :result/data {:id id :prompt match :url result-url}}))
      (catch Exception e
        (error "banana: Gemini image generation error:" (.getMessage e))
        {:result/error (str "Image generation failed: " (.getMessage e))}))
    {:result/error
     "Gemini API is not configured. Set `gemini.api.key` in config."}))

(cmd-hook #"banana"
  #".+" banana-cmd)
