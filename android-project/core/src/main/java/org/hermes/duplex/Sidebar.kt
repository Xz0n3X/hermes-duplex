package org.hermes.duplex

import org.json.JSONArray
import org.json.JSONObject

/**
 * What the sidebar shows: the projects, and the chats in the open one.
 *
 * The bridge owns all of it. A project is a directory there, with its own
 * memory and its own chats, and the phone's copy is only a view — so this
 * holds whatever the last `projects` event said and nothing it inferred.
 * Getting that wrong would mean a sidebar that disagrees with which memory
 * the agent is actually reading.
 */
object Sidebar {

    data class Project(val name: String, val path: String, val chats: Int)

    data class Chat(val id: String, val title: String, val updatedAt: Double)

    /** One working directory a command-line agent has conversations for. */
    data class CliProject(
        val key: String,
        val name: String,
        val path: String,
        val sessions: Int,
    )

    /** One of those conversations. */
    data class CliSession(
        val id: String,
        val title: String,
        val updatedAt: Double,
    )

    /**
     * The command-line agent's half of the sidebar.
     *
     * Absent unless the bridge is handing turns to one: these are
     * conversations you can only continue while that agent is the engine, and
     * a list of them under any other setting is a list of dead ends.
     * `enabled` is the bridge's word for that, not a guess made here from the
     * switch — the CLI also has to actually exist on that machine.
     */
    data class Cli(
        val enabled: Boolean = false,
        /** Which command-line agent these belong to: "claude", "codex". */
        val id: String = "",
        /** What to call it on screen, as the bridge names it. */
        val label: String = "",
        val projects: List<CliProject> = emptyList(),
        val sessions: List<CliSession> = emptyList(),
        val activeKey: String = "",
        val activeSession: String = "",
    ) {
        val activeProject: CliProject?
            get() = projects.firstOrNull { it.key == activeKey }
    }

    @Volatile
    var projects: List<Project> = emptyList()
        private set

    @Volatile
    var chats: List<Chat> = emptyList()
        private set

    @Volatile
    var cli: Cli = Cli()
        private set

    /** The open project's path, as the bridge reports it. */
    @Volatile
    var activePath: String = ""
        private set

    @Volatile
    var activeChatId: String = ""
        private set

    /** Bumped on every change, so a re-attaching screen can tell it missed one. */
    @Volatile
    var revision: Int = 0
        private set

    val activeProject: Project?
        get() = projects.firstOrNull { it.path == activePath }

    val activeChat: Chat?
        get() = chats.firstOrNull { it.id == activeChatId }

    @Synchronized
    fun accept(event: JSONObject) {
        projects = event.optJSONArray("items").objects().map {
            Project(
                name = it.optString("name"),
                path = it.optString("path"),
                chats = it.optInt("chats"),
            )
        }
        chats = event.optJSONArray("chats").objects().map {
            Chat(
                id = it.optString("id"),
                title = it.optString("title").ifEmpty { "New chat" },
                updatedAt = it.optDouble("updated_at", 0.0),
            )
        }
        activePath = event.optString("active")
        activeChatId = event.optString("chat_id")
        cli = event.optJSONObject("agent").let { section ->
            if (section == null || !section.optBoolean("enabled")) Cli()
            else Cli(
                enabled = true,
                id = section.optString("id"),
                label = section.optString("label").ifEmpty { "CLI" },
                projects = section.optJSONArray("projects").objects().map {
                    CliProject(
                        key = it.optString("key"),
                        name = it.optString("name"),
                        path = it.optString("path"),
                        sessions = it.optInt("sessions"),
                    )
                },
                sessions = section.optJSONArray("sessions").objects().map {
                    CliSession(
                        id = it.optString("id"),
                        title = it.optString("title").ifEmpty { "(untitled)" },
                        updatedAt = it.optDouble("updated_at", 0.0),
                    )
                },
                activeKey = section.optString("active"),
                activeSession = section.optString("session_id"),
            )
        }
        revision++
    }

    @Synchronized
    fun clear() {
        projects = emptyList()
        chats = emptyList()
        cli = Cli()
        activePath = ""
        activeChatId = ""
        revision++
    }

    private fun JSONArray?.objects(): List<JSONObject> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optJSONObject(it) }
    }
}
