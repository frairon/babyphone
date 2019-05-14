package babyphone.frosi.babyphone
import android.content.Context
import android.graphics.drawable.Drawable
import android.support.v4.view.PagerAdapter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout


internal class CustomPagerAdapter(val mContext: Context) : PagerAdapter() {
    var mLayoutInflater: LayoutInflater
    val mPageList: MutableList<Drawable> = ArrayList<Drawable>()

    init {
        mLayoutInflater = mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
    }

    override fun getCount(): Int {
        return mPageList.count()
    }

    override fun isViewFromObject(view: View, `object`: Any): Boolean {
        return view === `object` as LinearLayout
    }

    override fun instantiateItem(container: ViewGroup, position: Int): Any {
        val itemView = mLayoutInflater.inflate(R.layout.pager_item, container, false)

        val imageView = itemView.findViewById(R.id.pager_image) as ImageView
        imageView.setImageDrawable(mPageList.get(position))

        container.addView(itemView)

        return itemView
    }

    override fun destroyItem(container: ViewGroup, position: Int, `object`: Any) {
        container.removeView(`object` as LinearLayout)
    }
}