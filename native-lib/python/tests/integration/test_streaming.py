import json
from pathlib import Path

import pytest

import dataweave


def _staged_native_library() -> Path:
    native_dir = Path(__file__).resolve().parents[2] / "src" / "dataweave" / "native"
    return next(
        path
        for path in (
            native_dir / "dwlib.dylib",
            native_dir / "dwlib.so",
            native_dir / "dwlib.dll",
        )
        if path.is_file()
    )


@pytest.mark.integration
@pytest.mark.parametrize(
    "create_stream",
    [
        lambda runtime: runtime.run_streaming("output application/json --- 1"),
        lambda runtime: runtime.run_transform(
            "output application/json --- payload",
            [b"null"],
        ),
    ],
    ids=["run-streaming", "run-transform"],
)
def test_real_native_precreated_stream_rejects_stale_generation(create_stream):
    lib_path = str(_staged_native_library())
    keeper = dataweave.DataWeave(lib_path)
    runtime = dataweave.DataWeave(lib_path)
    keeper.initialize()
    runtime.initialize()
    stream = create_stream(runtime)
    old_handle = runtime._native.handle
    try:
        runtime.cleanup()
        runtime.initialize()
        assert runtime._native.handle != old_handle

        with pytest.raises(dataweave.DataWeaveError, match="stale engine generation"):
            next(stream)
    finally:
        stream.close()
        runtime.cleanup()
        keeper.cleanup()


@pytest.mark.integration
def test_run_streaming_returns_chunks_and_metadata(collect_stream):
    output, metadata = collect_stream(dataweave.run_streaming("output application/json --- {a: 1, b: 2}"))

    text = output.decode(metadata.charset or "utf-8")
    assert output
    assert '"a": 1' in text or '"a":1' in text
    assert metadata.success is True
    assert metadata.mime_type == "application/json"


@pytest.mark.integration
def test_run_streaming_splits_large_output_into_multiple_chunks(collect_stream):
    stream = dataweave.run_streaming('output application/json --- (1 to 5000) map {id: $, name: "item_" ++ $}')
    chunks = list(stream)
    output = b"".join(chunks)
    metadata = stream.metadata

    text = output.decode(metadata.charset or "utf-8")
    assert metadata.success is True
    assert len(chunks) > 1
    assert '"id": 5000' in text or '"id":5000' in text


@pytest.mark.integration
def test_run_streaming_returns_error_metadata(collect_stream):
    output, metadata = collect_stream(dataweave.run_streaming("output application/json --- invalid_var"))

    assert metadata.success is False
    assert metadata.error is not None
    assert output == b""


@pytest.mark.integration
def test_run_streaming_accepts_input_bindings(collect_stream):
    output, metadata = collect_stream(dataweave.run_streaming("num1 + num2", {"num1": 25, "num2": 17}))

    assert metadata.success is True
    assert output.decode(metadata.charset or "utf-8").strip() == "42"


@pytest.mark.integration
def test_run_transform_streams_iterable_input(collect_stream):
    stream = dataweave.run_transform(
        "output application/json\n---\npayload map ($ * 2)",
        input_stream=[b"[10, 20, 30, 40, 50]"],
        input_mime_type="application/json",
    )
    output, metadata = collect_stream(stream)

    text = output.decode(metadata.charset or "utf-8")
    assert metadata.success is True
    assert "20" in text
    assert "100" in text


@pytest.mark.integration
def test_run_transform_reads_chunked_input(collect_stream):
    input_data = b"[" + b",".join(f'{{"id":{index}}}'.encode() for index in range(1, 1001)) + b"]"

    def chunked():
        for index in range(0, len(input_data), 4096):
            yield input_data[index:index + 4096]

    stream = dataweave.run_transform(
        "output application/json\n---\nsizeOf(payload)",
        input_stream=chunked(),
        input_mime_type="application/json",
    )
    output, metadata = collect_stream(stream)

    assert metadata.success is True
    assert output.decode(metadata.charset or "utf-8") == "1000"


@pytest.mark.integration
def test_run_transform_preserves_large_single_input_chunk(collect_stream):
    payload = json.dumps([{"id": index, "name": f"item_{index}", "value": index * 3} for index in range(1, 2001)]).encode()
    assert len(payload) > 8192

    stream = dataweave.run_transform(
        "output application/json\n---\nsizeOf(payload)",
        input_stream=iter([payload]),
        input_mime_type="application/json",
    )
    output, metadata = collect_stream(stream)

    assert metadata.success is True
    assert output.decode(metadata.charset or "utf-8") == "2000"


@pytest.mark.integration
def test_run_transform_reads_file_input(collect_stream):
    xml_path = Path(__file__).resolve().parents[1] / "person.xml"
    with xml_path.open("rb") as source:
        stream = dataweave.run_transform(
            "output application/csv header=true\n---\n[payload.person]",
            input_stream=iter(lambda: source.read(4096), b""),
            input_mime_type="application/xml",
            input_charset="UTF-16",
        )
        output, metadata = collect_stream(stream)

    text = output.decode(metadata.charset or "utf-8")
    assert metadata.success is True
    assert "Billy" in text
    assert "31" in text
