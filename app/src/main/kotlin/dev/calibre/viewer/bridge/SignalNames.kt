// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// The wire-protocol signal inventory from web_view.py:ViewerBridge — the
// complete set for both directions. SIGNALS_JS_TO_HOST is what
// python_comm._register_signals() receives: the JS side binds
// to_python.<name> for each entry, so a missing name means a TypeError the
// moment the viewer tries to emit it. Keep in sync with upstream only by
// re-reading web_view.py when viewer.js is rebuilt.

package dev.calibre.viewer.bridge

object SignalNames {

    /** from_js signals (JS → host). Registered into the page after qt-ready. */
    val JS_TO_HOST: List<String> = listOf(
        "view_created",
        "on_iframe_ready",
        "content_file_changed",
        "update_last_read_position",
        "set_session_data",
        "set_local_storage",
        "reload_book",
        "toggle_toc",
        "toggle_bookmarks",
        "toggle_highlights",
        "toggle_profiles",
        "new_bookmark",
        "toggle_inspector",
        "toggle_lookup",
        "show_search",
        "search_result_not_found",
        "search_result_discovered",
        "find_next",
        "update_current_toc_nodes",
        "toggle_full_screen",
        "report_cfi",
        "ask_for_open",
        "selection_changed",
        "autoscroll_state_changed",
        "read_aloud_state_changed",
        "copy_selection",
        "view_image",
        "copy_image",
        "change_background_image",
        "overlay_visibility_changed",
        "reference_mode_changed",
        "show_loading_message",
        "show_error",
        "export_shortcut_map",
        "print_book",
        "clear_history",
        "reset_interface",
        "quit",
        "customize_toolbar",
        "scrollbar_context_menu",
        "close_prep_finished",
        "highlights_changed",
        "open_url",
        "speak_simple_text",
        "tts",
        "edit_book",
        "show_book_folder",
        "show_help",
        "html_input_focusin",
        "update_reading_rates",
        "reset_reading_rates",
        "profile_op",
    )
}
