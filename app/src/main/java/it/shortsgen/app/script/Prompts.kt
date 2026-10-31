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
  "scenes": [
    {"narration": "what the narrator says", "image_prompt": "description of the image for this scene", "search_query": "2-4 simple English keywords to find stock footage"}
  ]
}

Rules:
- Exactly ${ScriptParser.SCENE_COUNT} scenes.
- Scene 1 is a strong hook that creates curiosity in the first seconds.
- The last scene ends with a punchy closing line and invites viewers to follow for more curiosities.
- Each narration is 15 to 22 words; the whole script is 110 to 145 words (about 40-50 seconds of speech).
- Narration is plain spoken text: no emojis, no hashtags, no stage directions, no brackets.
- Each search_query is 2 to 4 simple, concrete English keywords describing what is visible in the scene, suitable for searching a stock video site (for example "octopus underwater", "night sky stars", "ancient egypt pyramids"). No abstract words, no names of people.
- Each image_prompt is a vivid, detailed description for an AI image generator: vertical 9:16 composition, photorealistic or cinematic, dramatic lighting, and it must not contain any text, letters, logos or watermarks.
""".trim()
    }

    fun imagePrompt(scenePrompt: String): String =
        "$scenePrompt. Vertical 9:16 portrait composition, highly detailed, cinematic lighting, " +
            "sharp focus. No text, no letters, no captions, no watermark."
}
