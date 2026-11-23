package com.theo.patcher.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.theo.patcher.R
import com.theo.patcher.model.AppInfo

class AppListAdapter(
    private val onAppClick: (AppInfo) -> Unit
) : ListAdapter<AppInfo, AppListAdapter.ViewHolder>(DIFF) {

    private var fullList: List<AppInfo> = emptyList()

    fun submitFullList(list: List<AppInfo>) {
        fullList = list
        submitList(list)
    }

    fun filter(query: String) {
        val q = query.lowercase()
        submitList(if (q.isEmpty()) fullList
        else fullList.filter {
            it.appName.lowercase().contains(q) || it.packageName.lowercase().contains(q)
        })
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView    = view.findViewById(R.id.ivAppIcon)
        val name: TextView     = view.findViewById(R.id.tvAppName)
        val pkg: TextView      = view.findViewById(R.id.tvPackageName)
        val size: TextView     = view.findViewById(R.id.tvApkSize)
        val version: TextView  = view.findViewById(R.id.tvVersion)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = getItem(position)
        holder.icon.setImageDrawable(app.icon)
        holder.name.text = app.appName
        holder.pkg.text  = app.packageName
        holder.size.text = formatSize(app.apkSize)
        holder.version.text = app.versionName

        holder.itemView.setOnClickListener { onAppClick(app) }
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
            bytes >= 1024      -> "%.1f KB".format(bytes / 1024.0)
            else               -> "$bytes B"
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<AppInfo>() {
            override fun areItemsTheSame(a: AppInfo, b: AppInfo) = a.packageName == b.packageName
            override fun areContentsTheSame(a: AppInfo, b: AppInfo) = a == b
        }
    }
}
