/*
 * SPDX-FileCopyrightText: 2026 Sannidhya Roy <sannidhya@thenoton.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.systemvolumeprovider

import android.content.SharedPreferences
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.kde.kdeconnect.ui.PluginSettingsFragment
import org.kde.kdeconnect_tp.R
import org.kde.kdeconnect_tp.databinding.ItemStreamHeaderBinding
import org.kde.kdeconnect_tp.databinding.ListItemTwoTargetToggleBinding

internal class SystemVolumeProviderSettingsFragment : PluginSettingsFragment() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        // No XML preferences — UI is provided by onCreateView
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val recycler = RecyclerView(requireContext())
        recycler.id = R.id.streams_recycler
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.clipToPadding = false
        val padding = resources.getDimensionPixelSize(R.dimen.activity_vertical_margin)
        recycler.setPadding(0, padding, 0, padding)
        return recycler
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val prefs = requireContext().getSharedPreferences(
            AndroidSinksProvider.PREFS_NAME, android.content.Context.MODE_PRIVATE
        )
        val (knownStreams, unknownStreams) = AndroidSinksProvider.discoverStreams(requireContext())
            .partition { it.isKnown }

        val items = buildList {
            add(ListItem.Header(getString(R.string.systemvolumeprovider_category_standard)))
            knownStreams.forEach { add(ListItem.StreamRow(it)) }
            if (unknownStreams.isNotEmpty()) {
                add(ListItem.Header(getString(R.string.systemvolumeprovider_category_additional)))
                unknownStreams.forEach { add(ListItem.StreamRow(it)) }
            }
        }

        (view as RecyclerView).adapter = StreamAdapter(items, prefs)
    }

    private sealed interface ListItem {
        data class Header(val title: String) : ListItem
        data class StreamRow(val stream: StreamInfo) : ListItem
    }

    private inner class StreamAdapter(
        private val items: List<ListItem>,
        private val prefs: SharedPreferences
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemViewType(position: Int) = when (items[position]) {
            is ListItem.Header -> VIEW_TYPE_HEADER
            is ListItem.StreamRow -> VIEW_TYPE_STREAM
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                VIEW_TYPE_HEADER -> HeaderHolder(ItemStreamHeaderBinding.inflate(inflater, parent, false))
                else -> StreamHolder(ListItemTwoTargetToggleBinding.inflate(inflater, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is ListItem.Header -> (holder as HeaderHolder).bind(item.title)
                is ListItem.StreamRow -> (holder as StreamHolder).bind(item.stream)
            }
        }

        override fun getItemCount() = items.size

        inner class HeaderHolder(private val binding: ItemStreamHeaderBinding) :
            RecyclerView.ViewHolder(binding.root) {
            fun bind(title: String) {
                binding.headerTitle.text = title
            }
        }

        inner class StreamHolder(private val binding: ListItemTwoTargetToggleBinding) :
            RecyclerView.ViewHolder(binding.root) {

            fun bind(stream: StreamInfo) {
                val isEnabled = prefs.getBoolean(
                    AndroidSinksProvider.prefKeyEnabled(stream.name),
                    stream.streamType in AndroidSinksProvider.DEFAULT_ENABLED_TYPES
                )
                val customLabel = prefs.getString(
                    AndroidSinksProvider.prefKeyLabel(stream.name), null
                )?.takeIf { it.isNotEmpty() }

                binding.rowTitle.text = customLabel ?: stream.defaultDescription
                binding.rowSubtitle.text = if (customLabel != null) stream.defaultDescription
                                              else getString(R.string.systemvolumeprovider_tap_to_customize)
                binding.rowSubtitle.visibility = View.VISIBLE

                binding.rowSwitch.setOnCheckedChangeListener(null)
                binding.rowSwitch.isChecked = isEnabled
                updateDividerColor(isEnabled)

                binding.rowSwitch.setOnCheckedChangeListener { _, checked ->
                    prefs.edit()
                        .putBoolean(AndroidSinksProvider.prefKeyEnabled(stream.name), checked)
                        .apply()
                    updateDividerColor(checked)
                }

                binding.rowLabelArea.setOnClickListener {
                    showRenameDialog(stream, customLabel)
                }
            }

            private fun updateDividerColor(enabled: Boolean) {
                val colorAttr = if (enabled) androidx.appcompat.R.attr.colorPrimary
                                else com.google.android.material.R.attr.colorOutlineVariant
                val tv = TypedValue()
                binding.root.context.theme.resolveAttribute(colorAttr, tv, true)
                binding.rowDivider.setBackgroundColor(tv.data)
            }

            private fun showRenameDialog(stream: StreamInfo, currentLabel: String?) {
                val editText = EditText(requireContext()).apply {
                    hint = stream.defaultDescription
                    setText(currentLabel ?: "")
                    setSelection(text.length)
                }
                val horizontal = resources.getDimensionPixelSize(R.dimen.activity_horizontal_margin)
                val container = android.widget.FrameLayout(requireContext()).apply {
                    setPadding(horizontal, 0, horizontal, 0)
                    addView(editText)
                }
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.systemvolumeprovider_custom_label)
                    .setView(container)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        prefs.edit()
                            .putString(
                                AndroidSinksProvider.prefKeyLabel(stream.name),
                                editText.text.toString().trim()
                            )
                            .apply()
                        notifyItemChanged(bindingAdapterPosition)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }

    }

    companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_STREAM = 1

        fun newInstance(pluginKey: String): SystemVolumeProviderSettingsFragment =
            SystemVolumeProviderSettingsFragment().apply { setArguments(pluginKey) }
    }
}
