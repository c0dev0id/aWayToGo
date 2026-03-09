#!/bin/sh

bench() {
    echo make diag-bench MAXFPS=$1 PREFETCH=$2 ZOOM=$3 PIXEL_RATIO=$4 CROSS_SRC=$5 DURATION=5
    make diag-bench MAXFPS=$1 PREFETCH=$2 ZOOM=$3 PIXEL_RATIO=$4 CROSS_SRC="true" DURATION=5
    sleep 7
}

make log-clear
for fps in 20 30 50 60 120 240
do
    for prefetch in 1
    do
        for zoom in 14
        do
            for pxratio in "3.0"
            do
                bench $fps $prefetch $zoom $pxratio $coll
            done
        done
    done
done
make log

