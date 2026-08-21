package protocol

import (
	"bytes"
	"encoding/binary"
	"errors"
	"io"
	"testing"
)

func TestFrameRoundtrip(t *testing.T) {
	cases := []struct {
		typ     byte
		payload []byte
	}{
		{FrameAuth, []byte(`{"role":"phone","token":"t","proto":1}`)},
		{FrameAudio, bytes.Repeat([]byte{0xAB}, 1920)},
		{FramePing, nil},
		{FramePeerState, []byte{PeerOffline}},
	}
	for i, c := range cases {
		var buf bytes.Buffer
		if err := WriteFrame(&buf, c.typ, c.payload); err != nil {
			t.Fatalf("case %d: WriteFrame: %v", i, err)
		}
		gotTyp, gotPayload, err := ReadFrame(&buf)
		if err != nil {
			t.Fatalf("case %d: ReadFrame: %v", i, err)
		}
		if gotTyp != c.typ || !bytes.Equal(gotPayload, c.payload) {
			t.Fatalf("case %d: roundtrip mismatch: typ=0x%02x payload=%d bytes", i, gotTyp, len(gotPayload))
		}
	}
}

func TestReadFrameTooLarge(t *testing.T) {
	var buf bytes.Buffer
	var bogus [5]byte
	binary.BigEndian.PutUint32(bogus[1:], MaxPayloadSize+1)
	buf.Write(bogus[:])
	if _, _, err := ReadFrame(&buf); !errors.Is(err, ErrFrameTooLarge) {
		t.Fatalf("want ErrFrameTooLarge, got %v", err)
	}
}

func TestWriteFrameRejectsOversize(t *testing.T) {
	big := make([]byte, MaxPayloadSize+1)
	if err := WriteFrame(io.Discard, FrameAudio, big); !errors.Is(err, ErrFrameTooLarge) {
		t.Fatalf("want ErrFrameTooLarge, got %v", err)
	}
}

func TestReadFrameTruncated(t *testing.T) {
	var buf bytes.Buffer
	if err := WriteFrame(&buf, FramePong, []byte{1, 2, 3}); err != nil {
		t.Fatal(err)
	}
	truncated := buf.Bytes()[:4] // 头都不完整
	if _, _, err := ReadFrame(bytes.NewReader(truncated)); err == nil {
		t.Fatal("expect error on truncated frame")
	}
}
