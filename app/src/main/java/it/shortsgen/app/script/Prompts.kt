package it.shortsgen.app.script

object Prompts {

    private val CATEGORIES = listOf(
        "space and astronomy", "the human body", "animals", "ancient history", "oceans",
        "physics", "inventions and technology", "geography", "food science", "plants",
        "psychology", "dinosaurs and prehistory", "weather and climate", "language and words",
        "famous buildings", "insects", "chemistry", "medieval history", "mathematics", "sleep and dreams",
    )

    fun scriptPrompt(usedTopics: List<String>, seed: Int): String {
        val category = CATEGORIES[Math.floorMod(seed, CATEGORIES.size)]
        val avoid = if (usedTopics.isEmpty()) "none yet" else usedTopics.takeLast(60).joinToString("; ")
        return """
You are the scriptwriter of a viral YouTube Shorts channel about surprising curiosities and fun facts.
Pick ONE fresh, specific, true and verifiable curiosity. Suggested area for this video: $category (you may choose another area if you have a much better idea).
Do NOT reuse or closely paraphrase any of these topics already used on the channel: $avoid

Write everything in English. Reply ONLY with a JSON object, no markdown, with exactly this structure:
{
  "topic": "short name of the curiosity, max 8 words",
  "title": "catchy YouTube Shorts title, max 70 characters, no emojis",
  "description": "2-3 engaging sentences followed by 3-5 hashtags including #shorts",
  "tags": ["8 to 12 short tags"],
  "subject": "the main filmable subject of the whole video in 1 or 2 English words, e.g. jellyfish, volcano, honey bee",
  "subject_queries": ["a closely related, more common subject", "the subject's natural setting"],
  "theme": "one or two generic English words for the overall visual theme, e.g. chemistry, fire, space, ocean",
  "theme_queries": ["4 to 5 simple, very common things related to the theme that are easy to film"],
  "scenes": [
    {"narration": "what the narrator says", "image_prompt": "description of the image for this scene", "search_queries": ["first visual query", "second visual query", "third visual query"]}
  ]
}

Rules:
- Exactly ${ScriptParser.SCENE_COUNT} scenes.
- Scene 1 is a strong hook that creates curiosity in the first seconds.
- The last scene ends with a punchy closing line and invites viewers to follow for more curiosities.
- Each narration is 15 to 22 words; the whole script is 110 to 145 words (about 40-50 seconds of speech).
- Narration is plain spoken text: no emojis, no hashtags, no stage directions, no brackets.
- Each scene has exactly 3 search_queries for a stock video site, best first. Each query is 1 to 3 concrete English words naming things a camera can film: objects, animals, places or actions (for example "octopus swimming", "coral reef", "lab test tubes", "candle flame", "night sky stars"). NEVER abstract concepts (no "mystery", "fact", "science", "history", "danger", "idea"), no names of people, no brands. Make the 3 queries different from each other and simpler from the first to the third.
- "subject" is the concrete thing the video is about and that a camera can film (an animal, object, place or phenomenon). It is added automatically to every search, so the search_queries should describe what the subject is doing or where it is (e.g. subject "jellyfish": "glowing in dark water", "pulsing tentacles close up").
- "subject_queries" are 2 fallback searches for the opening shot, used only if the subject itself has no footage: first a closely related, more common subject, then the subject's natural setting (for subject "siphonophore": ["jellyfish", "deep sea"]). Concrete and filmable, never abstract.
- Scene 1 (the hook) is illustrated only with footage of the subject itself, so its narration should be about the subject.
- Search queries must never ask for people, faces or crowds unless the curiosity is about people.
- "theme_queries" are 4 or 5 generic but concrete subjects linked to the theme, one or two words each, that every stock video site has (for a video about seeds: "tree", "forest", "seed pod", "plant", "flower field"). They are used when a scene's own queries find nothing.
- "theme" is a very common visual subject for the whole video that always has stock footage (for example "chemistry", "fire", "space", "ocean", "forest", "city").
- Each image_prompt is a vivid, detailed description for an AI image generator: vertical 9:16 composition, photorealistic or cinematic, dramatic lighting, and it must not contain any text, letters, logos or watermarks.
""".trim()
    }

    fun imagePrompt(scenePrompt: String): String =
        "$scenePrompt. Vertical 9:16 portrait composition, highly detailed, cinematic lighting, " +
            "sharp focus. No text, no letters, no captions, no watermark."
}
