/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package app.lawnchair.search.adapter

import android.app.PendingIntent
import android.app.search.SearchAction
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.os.UserHandle
import java.util.Objects
import kotlinx.parcelize.Parcelize

/**
 * Represents a searchable action info that can be called from another process
 * or within the client process.
 *
 * @hide
 */
@Parcelize
data class SearchActionCompat(
    /**
     * Returns the unique id of this object.
     */
    val id: String,
    /**
     * Returns a title representing the action.
     */
    val title: CharSequence,
    /**
     * Returns an icon representing the action.
     */
    val icon: Icon?,
    /**
     * Returns a subtitle representing the action.
     */
    val subtitle: CharSequence?,
    /**
     * Returns a content description representing the action.
     */
    val contentDescription: CharSequence?,
    /**
     * Returns the action intent.
     */
    val pendingIntent: PendingIntent?,
    /**
     * Returns the intent.
     */
    val intent: Intent?,
    /**
     * Returns the user handle.
     */
    val userHandle: UserHandle?,
    /**
     * Returns the extra bundle for this object.
     */
    val extras: Bundle?,
) : Parcelable {

    init {
        check(!(this.pendingIntent == null && this.intent == null)) { "At least one type of intent should be available." }
        check(!(this.pendingIntent != null && this.intent != null)) { "Only one type of intent should be available." }
    }

    private constructor(from: SearchAction) : this(
        from.id,
        from.title,
        from.icon,
        from.subtitle,
        from.contentDescription,
        from.pendingIntent,
        from.intent,
        from.userHandle,
        from.extras,
    )

    override fun equals(other: Any?): Boolean = when {
        this === other -> true
        other !is SearchActionCompat -> false
        else -> id == other.id && title == other.title
    }

    override fun hashCode(): Int = Objects.hash(id, title)

    /**
     * Returns true if [other] looks and acts the same as this action in a search result row.
     *
     * [equals] only compares [id] and [title]. This also compares the subtitle, the icon source and
     * where a tap goes, without relying on [Icon], [Intent] or [Bundle] equality (they compare by
     * reference and are rebuilt for every result set).
     */
    fun isContentSameAs(other: SearchActionCompat?): Boolean {
        if (this === other) return true
        if (other == null) return false
        return id == other.id &&
            title == other.title &&
            subtitle == other.subtitle &&
            contentDescription == other.contentDescription &&
            userHandle == other.userHandle &&
            pendingIntent == other.pendingIntent &&
            intent.isSameTargetAs(other.intent) &&
            icon.hasSameSourceAs(other.icon) &&
            CONTENT_EXTRAS.all { key -> extras.flag(key) == other.extras.flag(key) }
    }

    /**
     * A builder for search action object.
     *
     * @hide
     */
    class Builder(private val id: String, private val title: String) {
        private var icon: Icon? = null
        private var subtitle: CharSequence? = null
        private var contentDescription: CharSequence? = null
        private var pendingIntent: PendingIntent? = null
        private var intent: Intent? = null
        private var userHandle: UserHandle? = null
        private var extras: Bundle? = null

        /**
         * Sets the icon.
         */
        fun setIcon(icon: Icon?) = apply {
            this.icon = icon
        }

        /**
         * Sets the subtitle.
         */
        fun setSubtitle(subtitle: CharSequence?) = apply {
            this.subtitle = subtitle
        }

        /**
         * Sets the content description.
         */
        fun setContentDescription(contentDescription: CharSequence?) = apply {
            this.contentDescription = contentDescription
        }

        /**
         * Sets the pending intent.
         */
        fun setPendingIntent(pendingIntent: PendingIntent?) = apply {
            this.pendingIntent = pendingIntent
        }

        /**
         * Sets the user handle.
         */
        fun setUserHandle(userHandle: UserHandle?) = apply {
            this.userHandle = userHandle
        }

        /**
         * Sets the intent.
         */
        fun setIntent(intent: Intent?) = apply {
            this.intent = intent
        }

        /**
         * Sets the extra.
         */
        fun setExtras(extras: Bundle?) = apply {
            this.extras = extras
        }

        /**
         * Builds a new SearchActionCompat instance.
         */
        fun build(): SearchActionCompat = SearchActionCompat(
            id,
            title,
            icon,
            subtitle,
            contentDescription,
            pendingIntent,
            intent,
            userHandle,
            extras,
        )
    }

    companion object {
        fun wrap(action: SearchAction): SearchActionCompat = SearchActionCompat(action)

        /** Boolean [extras] read by `SearchResultIcon.bindFromAction`. */
        private val CONTENT_EXTRAS = arrayOf(
            "should_start_for_result",
            "should_start",
            "badge_with_package",
            "badge_with_component_name",
            "primary_icon_from_title",
        )
    }
}

private fun Bundle?.flag(key: String): Boolean = this?.getBoolean(key, false) ?: false

private fun Intent?.isSameTargetAs(other: Intent?): Boolean = if (this == null || other == null) {
    this === other
} else {
    filterEquals(other)
}

/**
 * Compares where two icons come from rather than the [Icon] objects. Resource and URI icons are
 * compared by resource or URI. Bitmap icons are rebuilt for every result set, so they count as the
 * same: their pixels follow from the kind of target, its id and its title, and where they do not
 * (contact photo, file thumbnail) the target carries [SearchTargetCompat.EXTRA_ICON_KEY].
 */
private fun Icon?.hasSameSourceAs(other: Icon?): Boolean {
    if (this === other) return true
    if (this == null || other == null) return false
    // Icon.getType() and the per-type getters are API 28+; older releases rely on the id and EXTRA_ICON_KEY.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return true
    if (type != other.type) return false
    return when (type) {
        Icon.TYPE_RESOURCE -> resId == other.resId && resPackage == other.resPackage
        Icon.TYPE_URI, Icon.TYPE_URI_ADAPTIVE_BITMAP -> uri == other.uri
        Icon.TYPE_BITMAP, Icon.TYPE_ADAPTIVE_BITMAP -> true
        else -> false
    }
}
