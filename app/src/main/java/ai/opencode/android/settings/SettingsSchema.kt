package ai.opencode.android.settings

import ai.opencode.android.R
import ai.opencode.android.data.model.PermissionPolicy
import androidx.annotation.StringRes

enum class SettingType { BOOL, INT, LONG, FLOAT, STRING, ENUM, JSON }

enum class SettingGroup(@StringRes val title: Int) {
    CONNECTION(R.string.schema_group_connection),
    AGENT(R.string.schema_group_agent),
    CHAT(R.string.schema_group_chat),
    APPEARANCE(R.string.schema_group_appearance),
    LOCAL_SERVER(R.string.schema_group_local),
    NETWORK(R.string.schema_group_network),
    UPDATES(R.string.schema_group_updates),
    PHONE(R.string.schema_group_phone),
    INTERNAL(R.string.schema_group_internal),
    OTHER(R.string.schema_group_other),
}

/**
 * One stored preference as the "All settings" screen shows it. The screen is generated from this
 * list, so a new setting needs one entry here and nothing else in the UI.
 */
data class SettingSpec(
    val key: String,
    @StringRes val title: Int,
    val group: SettingGroup,
    val type: SettingType,
    val min: Long? = null,
    val max: Long? = null,
    val options: List<String> = emptyList(),
    /** Bookkeeping the app writes itself; hidden unless the user asks for it. */
    val internal: Boolean = false,
    /** Changing it only takes effect after the in-app server restarts. */
    val needsRestart: Boolean = false,
) {
    fun validate(raw: String): Result<Any> = runCatching {
        val text = raw.trim()
        when (type) {
            SettingType.BOOL -> text.toBooleanStrictOrNull() ?: error("true / false")
            SettingType.INT -> (text.toIntOrNull() ?: error("integer")).also { checkRange(it.toLong()) }
            SettingType.LONG -> (text.toLongOrNull() ?: error("integer")).also { checkRange(it) }
            SettingType.FLOAT -> text.toFloatOrNull() ?: error("number")
            SettingType.ENUM -> text.takeIf { it in options } ?: error(options.joinToString(" / "))
            SettingType.JSON -> raw.also { if (it.isNotBlank()) kotlinx.serialization.json.Json.parseToJsonElement(it) }
            SettingType.STRING -> raw
        }
    }

    private fun checkRange(value: Long) {
        if (min != null && value < min) error("≥ $min")
        if (max != null && value > max) error("≤ $max")
    }

    companion object {
        /** Spec for a stored key nobody described (older versions, plugins): typed by its value. */
        fun unknown(key: String, value: Any?): SettingSpec = SettingSpec(
            key = key,
            title = R.string.schema_unknown,
            group = SettingGroup.OTHER,
            type = when (value) {
                is Boolean -> SettingType.BOOL
                is Int -> SettingType.INT
                is Long -> SettingType.LONG
                is Float, is Double -> SettingType.FLOAT
                else -> SettingType.STRING
            },
        )
    }
}

object SettingsSchema {

    private inline fun <reified E : Enum<E>> names(): List<String> = enumValues<E>().map { it.name }

    val all: List<SettingSpec> = listOf(
        SettingSpec("connection_mode", R.string.schema_connection_mode, SettingGroup.CONNECTION, SettingType.ENUM, options = names<ConnectionMode>()),
        SettingSpec("server_url", R.string.schema_server_url, SettingGroup.CONNECTION, SettingType.STRING),
        SettingSpec("auth_mode", R.string.schema_auth_mode, SettingGroup.CONNECTION, SettingType.ENUM, options = names<AuthMode>()),
        SettingSpec("auth_username", R.string.schema_auth_username, SettingGroup.CONNECTION, SettingType.STRING),
        SettingSpec("allow_lan_cleartext", R.string.schema_allow_lan_cleartext, SettingGroup.CONNECTION, SettingType.BOOL),
        SettingSpec("server_profiles", R.string.schema_server_profiles, SettingGroup.CONNECTION, SettingType.JSON),
        SettingSpec("offline_queue", R.string.schema_offline_queue, SettingGroup.CONNECTION, SettingType.BOOL),

        SettingSpec("model_provider", R.string.schema_model_provider, SettingGroup.AGENT, SettingType.STRING),
        SettingSpec("model_id", R.string.schema_model_id, SettingGroup.AGENT, SettingType.STRING),
        SettingSpec("agent", R.string.schema_agent, SettingGroup.AGENT, SettingType.STRING),
        SettingSpec("permission_policy", R.string.schema_permission_policy, SettingGroup.AGENT, SettingType.ENUM, options = names<PermissionPolicy>()),
        SettingSpec("system_prompts", R.string.schema_system_prompts, SettingGroup.AGENT, SettingType.JSON),
        SettingSpec("active_system_prompt", R.string.schema_active_system_prompt, SettingGroup.AGENT, SettingType.STRING),
        SettingSpec("model_context_limits", R.string.schema_model_context_limits, SettingGroup.AGENT, SettingType.JSON),
        SettingSpec("session_context_limits", R.string.schema_session_context_limits, SettingGroup.AGENT, SettingType.JSON),

        SettingSpec("chat_style", R.string.schema_chat_style, SettingGroup.CHAT, SettingType.ENUM, options = names<ChatStyle>()),
        SettingSpec("chat_monospace", R.string.schema_chat_monospace, SettingGroup.CHAT, SettingType.BOOL),
        SettingSpec("chat_expand_reasoning", R.string.schema_chat_expand_reasoning, SettingGroup.CHAT, SettingType.BOOL),
        SettingSpec("chat_expand_tools", R.string.schema_chat_expand_tools, SettingGroup.CHAT, SettingType.BOOL),
        SettingSpec("chat_tool_lines", R.string.schema_chat_tool_lines, SettingGroup.CHAT, SettingType.INT, min = 0, max = 20),
        SettingSpec("chat_send_on_enter", R.string.schema_chat_send_on_enter, SettingGroup.CHAT, SettingType.BOOL),
        SettingSpec("chat_timestamps", R.string.schema_chat_timestamps, SettingGroup.CHAT, SettingType.BOOL),
        SettingSpec("chat_cost", R.string.schema_chat_cost, SettingGroup.CHAT, SettingType.BOOL),
        SettingSpec("chat_context_warn", R.string.schema_chat_context_warn, SettingGroup.CHAT, SettingType.INT, min = 10, max = 99),
        SettingSpec("chat_context_danger", R.string.schema_chat_context_danger, SettingGroup.CHAT, SettingType.INT, min = 10, max = 100),
        SettingSpec("chat_auto_compact", R.string.schema_chat_auto_compact, SettingGroup.CHAT, SettingType.INT, min = 0, max = 99),
        SettingSpec("chat_haptics", R.string.schema_chat_haptics, SettingGroup.CHAT, SettingType.BOOL),
        SettingSpec("chat_playful_spinner", R.string.schema_chat_playful_spinner, SettingGroup.CHAT, SettingType.BOOL),
        SettingSpec("notifications", R.string.schema_notifications, SettingGroup.CHAT, SettingType.BOOL),

        SettingSpec("theme_mode", R.string.schema_theme_mode, SettingGroup.APPEARANCE, SettingType.ENUM, options = names<ThemeMode>()),
        SettingSpec("theme_palette", R.string.schema_theme_palette, SettingGroup.APPEARANCE, SettingType.ENUM, options = names<ThemePalette>()),
        SettingSpec("theme_pure_black", R.string.schema_theme_pure_black, SettingGroup.APPEARANCE, SettingType.BOOL),
        SettingSpec("chat_text_scale", R.string.schema_chat_text_scale, SettingGroup.APPEARANCE, SettingType.ENUM, options = names<TextScale>()),
        SettingSpec("ui_custom", R.string.schema_ui_custom, SettingGroup.APPEARANCE, SettingType.JSON),
        SettingSpec("ui_saved_themes", R.string.schema_ui_saved_themes, SettingGroup.APPEARANCE, SettingType.JSON),

        SettingSpec("local_server_enabled", R.string.schema_local_server_enabled, SettingGroup.LOCAL_SERVER, SettingType.BOOL),
        SettingSpec("local_server_port", R.string.schema_local_server_port, SettingGroup.LOCAL_SERVER, SettingType.INT, min = 1024, max = 65535, needsRestart = true),
        SettingSpec("local_artifact_url", R.string.schema_local_artifact_url, SettingGroup.LOCAL_SERVER, SettingType.STRING),
        SettingSpec("local_artifact_sha256", R.string.schema_local_artifact_sha256, SettingGroup.LOCAL_SERVER, SettingType.STRING),

        SettingSpec("proxy", R.string.schema_proxy, SettingGroup.NETWORK, SettingType.JSON, needsRestart = true),
        SettingSpec("plugin_catalog_url", R.string.schema_plugin_catalog_url, SettingGroup.NETWORK, SettingType.STRING),

        SettingSpec("opencode_auto_update", R.string.schema_opencode_auto_update, SettingGroup.UPDATES, SettingType.BOOL),
        SettingSpec("app_auto_update", R.string.schema_app_auto_update, SettingGroup.UPDATES, SettingType.BOOL),
        SettingSpec("app_auto_install", R.string.schema_app_auto_install, SettingGroup.UPDATES, SettingType.BOOL),

        SettingSpec("phone_storage", R.string.schema_phone_storage, SettingGroup.PHONE, SettingType.BOOL, needsRestart = true),
        SettingSpec("phone_android_tools", R.string.schema_phone_android_tools, SettingGroup.PHONE, SettingType.BOOL),

        SettingSpec("last_session_id", R.string.schema_last_session_id, SettingGroup.INTERNAL, SettingType.STRING, internal = true),
        SettingSpec("local_artifact_version", R.string.schema_local_artifact_version, SettingGroup.INTERNAL, SettingType.STRING, internal = true),
        SettingSpec("opencode_last_update_check", R.string.schema_opencode_last_update_check, SettingGroup.INTERNAL, SettingType.LONG, internal = true),
        SettingSpec("ui_revision", R.string.schema_ui_revision, SettingGroup.INTERNAL, SettingType.INT, internal = true),
    )

    private val byKey = all.associateBy { it.key }

    fun spec(key: String, value: Any?): SettingSpec = byKey[key] ?: SettingSpec.unknown(key, value)

    /** Keys tied to this device or install; profiles neither save nor overwrite them. */
    val deviceKeys: Set<String> = setOf(
        "last_session_id", "local_artifact_version", "opencode_last_update_check", "ui_revision",
    )

    /** Renders a stored value for display and for the edit field. */
    fun format(value: Any?): String = when (value) {
        null -> ""
        is Set<*> -> value.joinToString(", ")
        else -> value.toString()
    }
}
