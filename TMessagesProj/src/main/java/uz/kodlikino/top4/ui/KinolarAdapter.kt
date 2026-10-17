package uz.kodlikino.top4.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import org.telegram.messenger.R
import uz.kodlikino.top4.models.Movie

/** Kinolar feed adapter'i — poster + nom + meta + reyting; katak bosilsa ochiladi. */
class KinolarAdapter(
    private val ctx: Context,
    private var items: List<Movie>,
    private val onClick: (Movie) -> Unit
) : RecyclerView.Adapter<KinolarAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val poster: ImageView = view.findViewById(R.id.ivKinoPoster)
        val adult: TextView = view.findViewById(R.id.tvKinoAdult)
        val title: TextView = view.findViewById(R.id.tvKinoTitle)
        val meta: TextView = view.findViewById(R.id.tvKinoMeta)
        val rating: TextView = view.findViewById(R.id.tvKinoRating)
    }

    fun submit(list: List<Movie>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(ctx).inflate(R.layout.item_kino, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val movie = items[position]
        holder.title.text = movie.title
        val meta = listOf(movie.year, movie.genre, movie.duration)
            .map { it.trim() }.filter { it.isNotBlank() }.joinToString(" • ")
        holder.meta.text = meta
        holder.meta.visibility = if (meta.isBlank()) View.GONE else View.VISIBLE
        if (movie.rating > 0.0) {
            holder.rating.text = ctx.getString(R.string.kino_rating, formatRating(movie.rating))
            holder.rating.visibility = View.VISIBLE
        } else {
            holder.rating.visibility = View.GONE
        }
        holder.adult.visibility = if (movie.adult && !movie.isMultfilm) View.VISIBLE else View.GONE
        if (movie.imageUrl.isNotBlank()) {
            Glide.with(holder.poster)
                .load(movie.imageUrl)
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .placeholder(R.drawable.bg_classified_image_placeholder)
                .into(holder.poster)
        } else {
            holder.poster.setImageDrawable(null)
        }
        holder.itemView.setOnClickListener { onClick(movie) }
    }

    private fun formatRating(value: Double): String {
        return if (value % 1.0 == 0.0) value.toInt().toString()
        else String.format(java.util.Locale.US, "%.1f", value)
    }
}
