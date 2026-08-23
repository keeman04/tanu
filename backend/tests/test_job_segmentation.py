import job_segmentation as segmentation


def test_prefers_real_pause_near_each_target_boundary():
    old = segmentation.SEGMENT_SECONDS
    try:
        segmentation.SEGMENT_SECONDS = 420
        boundaries = segmentation.choose_boundaries(
            1000.0,
            [405.0, 419.5, 447.0, 838.0],
        )
        assert boundaries[0] == 0.0
        assert abs(boundaries[1] - 419.5) < 0.01
        assert abs(boundaries[2] - 838.0) < 0.01
        assert boundaries[-1] == 1000.0
    finally:
        segmentation.SEGMENT_SECONDS = old


def test_falls_back_to_exact_target_when_no_pause_is_available():
    old = segmentation.SEGMENT_SECONDS
    try:
        segmentation.SEGMENT_SECONDS = 420
        assert segmentation.choose_boundaries(900.0, []) == [0.0, 420.0, 900.0]
    finally:
        segmentation.SEGMENT_SECONDS = old


def test_short_recording_is_single_segment_boundary_pair():
    old = segmentation.SEGMENT_SECONDS
    try:
        segmentation.SEGMENT_SECONDS = 420
        assert segmentation.choose_boundaries(120.0, [60.0]) == [0.0, 120.0]
    finally:
        segmentation.SEGMENT_SECONDS = old
