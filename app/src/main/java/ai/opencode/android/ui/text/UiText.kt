package ai.opencode.android.ui.text

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/**
 * Text produced outside of composition (ViewModels) that must still follow the interface language.
 * [Res] resolves a string resource at render time; [Raw] carries text that is not ours to translate,
 * such as a server error message.
 */
sealed interface UiText {
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText

    data class Raw(val value: String) : UiText

    @Composable
    fun resolve(): String = when (this) {
        is Res -> stringResource(id, *args.map { if (it is UiText) it.resolve() else it }.toTypedArray())
        is Raw -> value
    }

    /** Same as [resolve] for code that runs outside composition (toasts, notifications). */
    fun resolve(context: android.content.Context): String = when (this) {
        is Res -> context.getString(id, *args.map { if (it is UiText) it.resolve(context) else it }.toTypedArray())
        is Raw -> value
    }

    companion object {
        fun of(@StringRes id: Int, vararg args: Any): UiText = Res(id, args.toList())

        fun raw(value: String): UiText = Raw(value)
    }
}
