package com.cliplink

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cliplink.databinding.ActivityHistoryBinding

/**
 * 历史记录列表。支持复制、删除、点击打开、清空。
 */
class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private lateinit var repository: LinkRepository
    private lateinit var adapter: LinkAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = LinkRepository(this)

        // 全面屏适配：顶栏背景延伸到状态栏下，底部按钮整体避开导航栏。
        InsetsHelper.applyWithToolbar(
            activity = this,
            toolbar = binding.toolbar,
            root = binding.root
        )

        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter = LinkAdapter(
            onOpen = { entry -> openLink(entry.url) },
            onCopy = { entry -> copyLink(entry.url) },
            onDelete = { entry ->
                repository.delete(entry.url)
                reload()
            }
        )

        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter

        binding.clearAllButton.setOnClickListener { confirmClear() }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        val items = repository.all()
        adapter.submit(items)
        binding.emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        binding.list.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        binding.clearAllButton.isEnabled = items.isNotEmpty()
    }

    private fun openLink(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(packageManager) == null) {
            toast(getString(R.string.open_failed_no_browser))
            return
        }
        runCatching { startActivity(intent) }
            .onFailure { toast(getString(R.string.open_failed_no_browser)) }
    }

    private fun copyLink(url: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("ClipLink", url))
        toast(getString(R.string.history_copied))
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle(R.string.action_clear_all)
            .setMessage(R.string.action_clear_all_confirm)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_confirm) { _, _ ->
                repository.clear()
                reload()
            }
            .show()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    /** 列表适配器。数据量小（默认上限 200），直接整体刷新即可。 */
    private class LinkAdapter(
        private val onOpen: (LinkRepository.Entry) -> Unit,
        private val onCopy: (LinkRepository.Entry) -> Unit,
        private val onDelete: (LinkRepository.Entry) -> Unit
    ) : RecyclerView.Adapter<LinkAdapter.Holder>() {

        private val items = mutableListOf<LinkRepository.Entry>()

        fun submit(newItems: List<LinkRepository.Entry>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_link, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(items[position], onOpen, onCopy, onDelete)
        }

        override fun getItemCount(): Int = items.size

        class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val urlText: TextView = itemView.findViewById(R.id.urlText)
            private val metaText: TextView = itemView.findViewById(R.id.metaText)

            fun bind(
                entry: LinkRepository.Entry,
                onOpen: (LinkRepository.Entry) -> Unit,
                onCopy: (LinkRepository.Entry) -> Unit,
                onDelete: (LinkRepository.Entry) -> Unit
            ) {
                urlText.text = entry.url
                metaText.text = itemView.context.getString(
                    R.string.history_meta,
                    entry.formattedTime(),
                    sourceLabel(entry.source)
                )

                itemView.setOnClickListener { onOpen(entry) }
                itemView.findViewById<View>(R.id.copyButton).setOnClickListener { onCopy(entry) }
                itemView.findViewById<View>(R.id.deleteButton).setOnClickListener { onDelete(entry) }
            }

            private fun sourceLabel(source: String): String = when (source) {
                "fallback" -> itemView.context.getString(R.string.source_fallback)
                "test" -> itemView.context.getString(R.string.source_test)
                else -> itemView.context.getString(R.string.source_shizuku)
            }
        }
    }
}
