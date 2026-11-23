package com.jarves.mh.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Termux Agent App ke AgentStore jaisa sada local store — Ideas / Goals / Feed
 * ki entries SharedPreferences me JSON ke roop me. Koi network nahi, koi
 * account nahi; sirf user ki apni lists.
 */
object AgentListsStore {

    data class Entry(val text: String, val sub: String = "", val done: Boolean = false)

    private const val PREFS = "agent_lists"

    const val KEY_IDEAS = "ideas"
    const val KEY_GOALS = "goals"
    const val KEY_FEED = "feed"

    fun load(context: Context, key: String): List<Entry> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, "[]")
        val out = mutableListOf<Entry>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Entry(o.getString("text"), o.optString("sub", ""), o.optBoolean("done", false)))
            }
        } catch (ignored: Exception) {
        }
        return out
    }

    fun save(context: Context, key: String, entries: List<Entry>) {
        try {
            val arr = JSONArray()
            for (e in entries) {
                val o = JSONObject()
                o.put("text", e.text)
                o.put("sub", e.sub)
                o.put("done", e.done)
                arr.put(o)
            }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(key, arr.toString()).apply()
        } catch (ignored: Exception) {
        }
    }
}
