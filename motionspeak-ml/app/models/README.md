# MediaPipe model bundles

`extract.py` uses the MediaPipe **Tasks** API for both hand and pose landmarks,
which requires two model bundles to be present in this directory:

- `hand_landmarker.task`
- `pose_landmarker_lite.task` (same bundle shipped on-device — see
  `motionspeak-mobile/app/src/main/assets/pose_landmarker_lite.task`)
- `face_landmarker.task` (MediaPipe Face Mesh landmarker used for facial landmarking and expression recognition)

The `.task` binaries are gitignored. Download them if needed:

```bash
curl -sSL -o app/models/hand_landmarker.task \
  https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task

curl -sSL -o app/models/pose_landmarker_lite.task \
  https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/1/pose_landmarker_lite.task

curl -sSL -o app/models/face_landmarker.task \
  https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task
```

Override the locations with the `HAND_LANDMARKER_MODEL` / `POSE_LANDMARKER_MODEL` / `FACE_LANDMARKER_MODEL`
env vars if you store them elsewhere.
