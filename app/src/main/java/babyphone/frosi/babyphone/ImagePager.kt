package babyphone.frosi.babyphone

import android.content.Context
import android.graphics.drawable.Drawable
import android.support.v4.view.PagerAdapter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout


internal class ImagePager(val mContext: Context, val maxcount: Int = 10) : PagerAdapter() {
    private var mLayoutInflater: LayoutInflater? = null
    private val mPageList: MutableList<Drawable> = ArrayList<Drawable>()

    fun addImage(img: Drawable) {
        mPageList.add(img)
        while (mPageList.size > maxcount) {
            mPageList.removeAt(0)
        }
        notifyDataSetChanged()
    }

    override fun getCount(): Int {
        return mPageList.count()
    }

    override fun isViewFromObject(view: View, `object`: Any): Boolean {
        return view === `object` as LinearLayout
    }

    override fun instantiateItem(container: ViewGroup, position: Int): Any {
        if (mLayoutInflater == null) {
            mLayoutInflater = mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        }

        val itemView = mLayoutInflater!!.inflate(R.layout.pager_item, container, false)

        val imageView = itemView.findViewById(R.id.pager_image) as ImageView
        imageView.setImageDrawable(mPageList.get(position))

        container.addView(itemView)

        return itemView
    }

    override fun destroyItem(container: ViewGroup, position: Int, `object`: Any) {
        container.removeView(`object` as LinearLayout)
    }
}