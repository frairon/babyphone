import datetime
import numpy as np
import matplotlib.pyplot as plt
import matplotlib.image as mpimg
from PIL import Image
import io

import logging


def createImage(pictureArray, volumes, dpi=72, chartHeight=0.2):
    picture = Image.fromarray(pictureArray)

    targetWidth = picture.width
    chartHeight = int(targetWidth*0.2)
    targetHeight = picture.height+chartHeight

    target = Image.new(mode='RGB', size=(targetWidth, targetHeight))

    target.paste(picture, (0, 0))

    fig, volAx = plt.subplots(facecolor='black',
                              edgecolor=None, frameon=False, nrows=1,
                              figsize=(targetWidth/dpi, chartHeight/dpi), dpi=dpi,
                              tight_layout=True,
                              )

    # configure chart to omit the labels etc
    fig.subplots_adjust(left=None, bottom=None, right=None,
                        top=None, wspace=None, hspace=0.0)

    volAx.patch.set_alpha(0.0)
    volAx.tick_params(
        bottom=False,
        left=False,
        right=False,
        top=False,
        labelsize=18,
        # labelbottom=False,
        # labelleft=False,
        labelright=False,
        labeltop=False,
        grid_alpha=0,
    )

    volAx.spines["left"].set_visible(False)
    volAx.spines["right"].set_visible(False)
    volAx.spines["top"].set_visible(False)
    # volAx.spines["bottom"].set_visible(False)

    volAx.set_ylim(0, 100)

    # plot the volume values
    volumesX = [datetime.datetime.fromtimestamp(x[0])
                for x in volumes]
    volumesY = [x[1]*100 for x in volumes]

    volAx.plot(volumesX, volumesY, linewidth=3)

    # save the voulme chart
    buf = io.BytesIO()
    fig.savefig(buf, dpi=dpi)
    # ... and reread into a PIL image
    buf.seek(0)
    chart = Image.open(buf)
    # copy the chart below the image
    target.paste(chart, (0, picture.height))

    output = io.BytesIO()
    target.save(output, format='PNG')

    output.seek(0)
    return output
