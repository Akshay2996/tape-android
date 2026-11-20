# Object measurement — models and datasets

🟡 In progress

> Tape measures an object by naming it with an on-device model and sizing it with the
> phone's depth, not by asking a model to guess its size. Datasets matter for the naming
> step and for testing; the size comes from geometry, so it can carry an honest ± bound.

Written 2026-11-17 for research. Figures are from each project's own paper or page, linked
in the table rows.

## How Tape does it today

| Step | What runs | Where | Gives |
|---|---|---|---|
| 1. Tap | Nothing runs before the tap | — | One camera frame |
| 2. Find | ML Kit Object Detection, bundled | On the phone | A 2-D box around the object under the tap |
| 3. Name | ML Kit Image Labeling, bundled (400+ labels) | On the phone | "Chair", "Laptop", … or nothing if unsure (< 60 %) |
| 4. Size | ARCore raw depth + detected planes | On the phone | An upright 3-D box: length, width, height, ± each |

The 2-D box from step 2 limits step 4: depth points outside the object's outline (as seen
from where you tapped) are never part of it. Nothing leaves the phone.

**Why not let a model estimate the size?** Single-image 3-D detectors (Cube R-CNN,
Objectron) learn typical sizes from training data. They are useful when there is no depth,
but their error is not observable from the reading itself, so Tape could not state an
honest tolerance for it. Depth plus geometry can.

## Models worth knowing

| Model | Does | On Android | Notes |
|---|---|---|---|
| [ML Kit Object Detection](https://developers.google.com/ml-kit/vision/object-detection) | Class-agnostic 2-D boxes | Bundled, offline | In Tape now |
| [ML Kit Image Labeling](https://developers.google.com/ml-kit/vision/image-labeling) | 400+ labels per image | Bundled, offline | In Tape now |
| [MediaPipe Object Detector](https://developers.google.com/edge/mediapipe/solutions/vision/object_detector) (EfficientDet-Lite0) | 2-D boxes **with** names, 80 COCO classes | `tasks-vision`, model in assets | One model instead of two; no "box/carton" class |
| [Cube R-CNN](https://arxiv.org/abs/2207.10660) | 3-D box (size, rotation) from one image, 97 categories | Not mobile-ready | Research reference |
| [Objectron](https://research.google/blog/announcing-the-objectron-dataset/) | 3-D box from one image, 9 categories | Legacy MediaPipe solution | Research reference |

## Datasets

| Dataset | What it is | Size | 3-D boxes | Useful for |
|---|---|---|---|---|
| [COCO](https://cocodataset.org) | Everyday photos | 80 classes, 1.5M instances | ❌ | Naming common objects |
| [Open Images V7](https://storage.googleapis.com/openimages/web/index.html) | Web photos | 1.9M images, 16M boxes, 600 classes | ❌ | Naming, more classes (incl. boxes) |
| [Objectron](https://github.com/google-research-datasets/Objectron) | Phone videos walking round one object, with AR poses | 14,819 videos, 4M images, 9 classes | ✅ | **Closest to Tape's use**: phone + AR session + 3-D box |
| [ARKitScenes](https://github.com/apple/ARKitScenes) | iPad LiDAR scans of rooms | 5,048 sequences, 1,661 scenes, 17 furniture classes | ✅ | Furniture sizes, depth ground truth |
| [CA-1M (Cubify Anything)](https://machinelearning.apple.com/research/cubify-anything) | ARKitScenes relabelled from laser scans | 400K+ objects, 1K+ scenes | ✅ | Accurate 3-D boxes for every indoor object |
| [Omni3D](https://arxiv.org/abs/2207.10660) | Six 3-D datasets combined | 234K images, 3M+ objects, 97 classes | ✅ | Single-image 3-D detection |
| [SUN RGB-D](https://rgbd.cs.princeton.edu) | RGB-D photos of rooms | 10,335 images, 64,595 3-D boxes | ✅ | Indoor objects with depth |
| [ScanNet](http://www.scan-net.org) | RGB-D video scans of rooms | 1,513 scans, 707 spaces | ✅ (via instance labels) | Room-scale reconstruction |

Licences differ: several are research-only. Check each before using it in a shipped app.

## What would help Tape most

1. **Your own ground-truth set.** Twenty to fifty household objects, each measured with a
   physical tape, then measured with Tape from three distances. It tests exactly what
   matters — is the true size inside the stated ± — on exactly your phone.
2. **Objectron, for testing.** Its videos come with AR camera poses and true 3-D boxes,
   so the segmentation and box fitting could be checked on real phone footage without
   recording anything new. (It has no depth images, so it tests fitting, not sensing.)
3. **A custom label list** only if ML Kit's names are wrong for your objects. ML Kit can
   load a custom TFLite classifier; training one on Open Images classes you care about
   (carton, sofa, wardrobe…) is a small project.

## Open questions

- Which objects do you measure most? That decides whether naming needs custom training.
- Should Tape remember sizes per name ("your sofa is 2.1 m") to flag a reading that
  disagrees with an earlier one?
