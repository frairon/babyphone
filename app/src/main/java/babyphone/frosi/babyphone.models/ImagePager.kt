package babyphone.frosi.babyphone.models

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.viewpager.widget.PagerAdapter
import babyphone.frosi.babyphone.Babyphone
import babyphone.frosi.babyphone.R
import io.reactivex.subjects.BehaviorSubject
import org.threeten.bp.Instant
import org.threeten.bp.LocalDateTime
import org.threeten.bp.ZoneId
import org.threeten.bp.format.DateTimeFormatter


class ImagePager(val mContext: Context, private val maxcount: Int = 10) : PagerAdapter() {
    private var mLayoutInflater: LayoutInflater? = null
    private val mPageList: MutableList<Babyphone.TimedDrawable> = ArrayList<Babyphone.TimedDrawable>()

    val sizeUpdated = BehaviorSubject.create<Int>()

    fun addImage(img: Babyphone.TimedDrawable) {
        // if we have that timestamp already, ignore it
        if (mPageList.find { d -> d.instant == img.instant } != null) {
            return
        }

        mPageList.add(img)

        while (mPageList.size > maxcount) {
            mPageList.removeAt(0)
        }

        sizeUpdated.onNext(this.count)
        notifyDataSetChanged()
    }

    override fun getCount(): Int {
        return mPageList.count()
    }

    override fun isViewFromObject(view: View, `object`: Any): Boolean {
        return view === `object` as RelativeLayout
    }

    override fun instantiateItem(container: ViewGroup, position: Int): Any {
        if (mLayoutInflater == null) {
            mLayoutInflater = mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        }
        val item = mPageList[position]
        val itemView = mLayoutInflater!!.inflate(R.layout.pager_item, container, false)

        // associate the item instant with the view.
        itemView.tag = item.instant

        val imageView = itemView.findViewById(R.id.pager_image) as ImageView
        imageView.setImageDrawable(item.drawable)

        val imageTime = itemView.findViewById(R.id.picture_time) as TextView
        val localTime = LocalDateTime.ofInstant(mPageList[position].instant, ZoneId.systemDefault())
        imageTime.text = DateTimeFormatter.ISO_LOCAL_TIME.format(localTime)

        container.addView(itemView)

        return itemView
    }

    override fun destroyItem(container: ViewGroup, position: Int, obj: Any) {
        container.removeView(obj as RelativeLayout)
    }

    override fun getItemPosition(obj: Any): Int {
        val item = obj as RelativeLayout
        val instant = item.tag as Instant

        val idx = mPageList.indexOfFirst { d -> d.instant == instant }
        return if (idx == -1) POSITION_NONE else idx
    }
}