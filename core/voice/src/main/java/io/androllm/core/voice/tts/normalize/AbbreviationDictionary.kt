package io.androllm.core.voice.tts.normalize

import java.util.Locale

/**
 * Acronyms/technical terms → their spoken names. Every expansion is a word
 * the VITS lexicon knows or an OOV-spellable letter sequence ("G P U" is
 * spelled letter by letter by the lexicon stage flawlessly).
 *
 * Easy to extend: add lines to [EXPANSIONS]. Lookups are case-insensitive.
 */
object AbbreviationDictionary {

    val EXPANSIONS: Map<String, String> = mapOf(
        // spelled out (letters)
        "AI" to "a i", "AGI" to "a g i", "API" to "a p i", "APK" to "a p k",
        "APP" to "app", "APPS" to "apps", "ASR" to "a s r", "CPU" to "c p u",
        "GPU" to "g p u", "GGUF" to "g g u f", "GPT" to "g p t",
        "RAM" to "ram", "ROM" to "room", "JNI" to "j n i", "SIM" to "s i m",
        "UI" to "u i", "UX" to "u x", "SSD" to "s s d", "HDD" to "h d d",
        "USB" to "u s b", "URL" to "u r l", "URI" to "u r i",
        "HTTP" to "h t t p", "HTTPS" to "h t t p s", "HTML" to "h t m l",
        "CSS" to "c s s", "XML" to "x m l", "JSON" to "j s o n",
        "YAML" to "y a m l", "SQL" to "s q l", "DB" to "d b", "DNS" to "d n s",
        "TCP" to "t c p", "UDP" to "u d p", "IP" to "i p", "VPN" to "v p n",
        "OS" to "o s", "OSX" to "o s x", "FPS" to "f p s", "DPI" to "d p i",
        "TTS" to "t t s", "STT" to "s t t", "ASR" to "a s r",
        "LLM" to "e l l m", "RAG" to "r a g", "DL" to "d l", "ML" to "m l",
        "NPC" to "n p c", "SMS" to "s m s", "MMS" to "m m s",
        "GPS" to "g p s", "NFC" to "n f c", "QR" to "q r", "RC" to "r c",
        "AIOS" to "a i o s", "IDE" to "i d e", "SDK" to "s d k",
        "JDK" to "j d k", "JVM" to "j v m", "GC" to "g c",
        "PDF" to "p d f", "JPEG" to "j p e g", "PNG" to "p n g", "GIF" to "j i f",
        "SVG" to "s v g", "MP3" to "m p three", "MP4" to "m p four",
        "AVI" to "a v i", "MKV" to "m k v",
        "SIM" to "s i m", "IMEI" to "i m e i", "IMGS" to "i m g s",
        "CC" to "c c", "BCC" to "b c c", "FYI" to "f y i", "TBD" to "t b d",
        "ETA" to "e t a", "VIP" to "v i p", "CV" to "c v", "ASAP" to "a s a p",
        // spoken out in full words
        "MHz" to "megahertz", "GHz" to "g h z", "kHz" to "kilohertz",
        "Hz" to "hertz", "Wi-Fi" to "wi fi", "wifi" to "wi fi",
        "UK" to "u k", "US" to "u s", "EU" to "e u", "UN" to "u n",
        "COVID" to "covid", "HTTP" to "h t t p",
        "E.G." to "for example", "E.G" to "for example",
        "I.E." to "that is", "I.E" to "that is",
        "A.K.A." to "also known as", "A.K.A" to "also known as",
        "ETC." to "etcetera", "U.S." to "u s", "U.K." to "u k",
        "C#" to "c sharp", "F#" to "f sharp",
        "VS" to "versus", "V." to "versus"
    )

    fun resolve(word: String): String? = EXPANSIONS[word.uppercase(Locale.ROOT)]
}