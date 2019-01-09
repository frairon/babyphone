"""A setuptools based setup module.
See:
https://packaging.python.org/en/latest/distributing.html
https://github.com/pypa/sampleproject
"""

import sys
from io import open
from os import path

from setuptools import find_packages, setup

here = path.abspath(path.dirname(__file__))

if sys.version_info < (3, 5):
    sys.exit('Sorry, need at least python3.5 for the Babyphone')

# Get the long description from the README file
with open(path.join(here, 'README.md'), encoding='utf-8') as f:
    long_description = f.read()

setup(
    name='babyphone',

    version='1.0.0',

    description='Babyphone server implementation',

    long_description=long_description,

    url='https://github.com/frairon/babyphone',

    keywords='rpi raspberry camera babyphone microphone',

    packages=find_packages(exclude=['contrib', 'docs', 'tests']),
    package_data={
    },

    install_requires=[
        'websockets',
        'psutil'],
)
