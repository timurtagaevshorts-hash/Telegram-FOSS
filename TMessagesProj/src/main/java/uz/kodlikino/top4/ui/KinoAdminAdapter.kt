package uz.kodlikino.top4.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.telegram.messenger.R
import uz.kodlikino.top4.models.Movie

/** Admin kino ro'yxati — har qatorda tahrirlash / o'chirish. */
class KinoAdminAdapter(
    private val ctx: Context,
    private var items: List<Movie>,
    private val onEdit: (Movie) -> Unit,
    private val onDelete: (Movie) -> Unit
) : RecyclerView.Adapter<KinoAdminAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.tvAdminTitle)
        val meta: TextView = view.findViewById(R.id.tvAdminMeta)
        val edit: View = view.findViewById(R.id.btnAdminEdit)
        val delete: View = view.findViewById(R.id.btnAdminDelete)
    }

    fun submit(list: List<Movie>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        return VH(LayoutInflater.from(ctx).inflate(R.layout.item_kino_admin, parent, false))
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val movie = items[position]
        holder.title.text = movie.title.ifBlank { "(nomsiz)" }
        val badge = when {
            movie.isMultfilm -> "MF"
            movie.adult -> "18+"
            else -> "OK"
        }
        val meta = listOf(badge, movie.category, movie.genre, movie.year)
            .map { it.trim() }.filter { it.isNotBlank() }.joinToString("  •  ")
        holder.meta.text = meta
        holder.edit.setOnClickListener { onEdit(movie) }
        holder.delete.setOnClickListener { onDelete(movie) }
    }
}
