package com.voicereact.app

import android.content.Context
import android.media.MediaPlayer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.util.Locale

data class VoiceClip(val file: File, val createdAtMs: Long, val durationMs: Int)

class VoiceClipRecyclerAdapter(
    private val clips: MutableList<VoiceClip>
) : RecyclerView.Adapter<VoiceClipRecyclerAdapter.ClipViewHolder>() {

    private var playingPlayer: MediaPlayer? = null
    private var playingPosition: Int = -1

    class ClipViewHolder(val root: FrameLayout) : RecyclerView.ViewHolder(root) {
        lateinit var playButton: ImageButton
        lateinit var durationLabel: TextView
        lateinit var progress: ProgressBar
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ClipViewHolder {
        val context = parent.context
        val row = FrameLayout(context)
        val bubble = LinearLayout(context)
        bubble.orientation = LinearLayout.HORIZONTAL
        bubble.gravity = Gravity.CENTER_VERTICAL
        bubble.setPadding(24, 16, 24, 16)
        bubble.setBackgroundColor(0xFF2A2F3A.toInt())

        val playButton = ImageButton(context)
        playButton.setImageResource(android.R.drawable.ic_media_play)
        playButton.background = null
        bubble.addView(playButton, LinearLayout.LayoutParams(72, 72))

        val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal)
        progress.max = 1000
        val progressParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        progressParams.marginStart = 16
        progressParams.marginEnd = 16
        bubble.addView(progress, progressParams)

        val durationLabel = TextView(context)
        durationLabel.setTextColor(0xFFFFFFFF.toInt())
        bubble.addView(durationLabel, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        row.addView(bubble, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            val m = 12
            setMargins(m, m, m, m)
        })

        val holder = ClipViewHolder(row)
        holder.playButton = playButton
        holder.durationLabel = durationLabel
        holder.progress = progress
        return holder
    }

    override fun onBindViewHolder(holder: ClipViewHolder, position: Int) {
        val clip = clips[position]
        holder.durationLabel.text = formatDuration(clip.durationMs)
        val isPlaying = position == playingPosition
        holder.playButton.setImageResource(
            if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        )
        holder.progress.progress = 0

        holder.playButton.setOnClickListener {
            if (isPlaying) {
                stopPlayback()
                notifyItemChanged(position)
            } else {
                playClip(holder.root.context, position, holder.progress)
            }
        }
    }

    override fun getItemCount(): Int = clips.size

    /** Call when a new recording finishes — new clip appears at the end and the list
     *  (when hosted in a RecyclerView whose stackFromEnd/reverseLayout is set for the
     *  "mic stays at bottom, messages grow upward" behavior) auto-scrolls to show it. */
    fun addClip(clip: VoiceClip, recyclerView: RecyclerView) {
        clips.add(clip)
        notifyItemInserted(clips.size - 1)
        recyclerView.post { recyclerView.scrollToPosition(clips.size - 1) }
    }

    private fun playClip(context: Context, position: Int, progress: ProgressBar) {
        stopPlayback()
        val clip = clips[position]
        val player = MediaPlayer()
        try {
            player.setDataSource(clip.file.absolutePath)
            player.prepare()
            player.setOnCompletionListener {
                stopPlayback()
                notifyItemChanged(position)
            }
            player.start()
            playingPlayer = player
            playingPosition = position
            notifyItemChanged(position)
        } catch (ignored: Throwable) {
            player.release()
        }
    }

    private fun stopPlayback() {
        try { playingPlayer?.stop() } catch (ignored: Throwable) { }
        try { playingPlayer?.release() } catch (ignored: Throwable) { }
        playingPlayer = null
        val prev = playingPosition
        playingPosition = -1
        if (prev >= 0 && prev < clips.size) notifyItemChanged(prev)
    }

    private fun formatDuration(ms: Int): String {
        val totalSeconds = ms / 1000
        return String.format(Locale.US, "0:%02d", totalSeconds)
    }
}
