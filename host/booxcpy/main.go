/*
 * Copyright 2026 Piyush Daiya
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 */

package main

import (
	"bufio"
	"bytes"
	"context"
	"encoding/binary"
	"errors"
	"flag"
	"fmt"
	"io"
	"net"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"regexp"
	"runtime"
	"strings"
	"sync"
	"syscall"
	"time"
	"sync/atomic"
)

const (
	pkgName          = "io.github.piyushdaiya.booxstream"
	mainActivity     = "io.github.piyushdaiya.booxstream/.MainActivity"
	serviceComponent = "io.github.piyushdaiya.booxstream/.stream.Vp8IvfStreamService"

	adbForwardPort   = 27183
	adbAbstractSock  = "booxstream_ivf"
	adbForwardTarget = "localabstract:" + adbAbstractSock
)

type Config struct {
	Serial   string
	Install  bool
	APKPath  string
	NoPlay   bool
	Record   bool
	Output   string
	Width    int
	Height   int
	FPS      int
	Bitrate  int
	Verbose  bool
	NoLaunch bool
	Preset   string
}

func main() {
	cmdName, cfg := parseArgs(os.Args[1:])

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	adb := &ADB{Serial: cfg.Serial, Verbose: cfg.Verbose}

	serial, err := ensureDevice(ctx, adb)
	dieIf(err)
	if cfg.Serial == "" {
		adb.Serial = serial
	}

	switch cmdName {
	case "stop":
		dieIf(stopRemote(ctx, adb, cfg))
		return
	case "status":
		dieIf(statusRemote(ctx, adb, cfg))
		return
	case "mirror":
	default:
		dieIf(fmt.Errorf("unknown command %q (supported: mirror, stop, status)", cmdName))
	}

	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, os.Interrupt, syscall.SIGTERM)

	var cleanupOnce sync.Once
	cleanup := func(reason string) {
		cleanupOnce.Do(func() {
			bg, cancelBg := context.WithTimeout(context.Background(), 4*time.Second)
			defer cancelBg()

			if cfg.Verbose && reason != "" {
				fmt.Fprintln(os.Stderr, "cleanup:", reason)
			}

			_ = adb.ForwardRemove(bg, adbForwardPort)
			_ = stopServiceQuiet(bg, adb, cfg.Verbose)
		})
	}

	go func() {
		<-sigCh
		fmt.Fprintln(os.Stderr, "\nStopping...")
		cancel()
		cleanup("signal")
	}()

	if cfg.Install {
		fmt.Println("Forcing APK install...")
	}
	if cfg.APKPath != "" {
		abs, _ := filepath.Abs(cfg.APKPath)
		cfg.APKPath = abs
	}

	dieIf(ensureInstalled(ctx, adb, cfg))

	_ = stopServiceQuiet(ctx, adb, cfg.Verbose)
	time.Sleep(1000 * time.Millisecond)

	_ = adb.ForwardRemove(ctx, adbForwardPort)
	time.Sleep(300 * time.Millisecond)

	dieIf(adb.Forward(ctx, adbForwardPort, adbForwardTarget))
	defer cleanup("defer")

	if !cfg.NoLaunch {
		dieIf(startMirroringActivity(ctx, adb, cfg))
		fmt.Println("Waiting for stream... (accept the screen-capture prompt on the device)")
	} else {
		fmt.Println("Skipping activity launch (--no-launch). Assuming app is already streaming.")
	}

	recordPath := ""
	if cfg.Record {
		recordPath = cfg.Output
		if recordPath == "" {
			recordPath = defaultRecordName()
		}
		if !strings.HasSuffix(strings.ToLower(recordPath), ".ivf") {
			recordPath += ".ivf"
		}
		fmt.Println("Recording to:", recordPath)
	} else if cfg.NoPlay {
		dieIf(errors.New("nothing to do: mirroring disabled (--silent/--no-mirror) and recording disabled (use --record)"))
	}

	addr := fmt.Sprintf("127.0.0.1:%d", adbForwardPort)
	waitCtx, waitCancel := context.WithTimeout(ctx, 60*time.Second)
	defer waitCancel()

	conn, prefix, err := waitForIvfStream(waitCtx, addr, cfg)
	dieIf(err)

	if cfg.NoPlay {
		dieIf(streamToRecordOnly(ctx, conn, prefix, recordPath))
		cleanup("record-only done")
		fmt.Println("Done.")
		return
	}

	dieIf(streamToPlayerAndOptionalRecord(ctx, adb.Serial, cfg, conn, prefix, recordPath))
	cleanup("mirror done")
	fmt.Println("Done.")
}

func parseArgs(argv []string) (string, Config) {
	cmd := "mirror"
	filtered := make([]string, 0, len(argv))
	for _, a := range argv {
		if strings.HasPrefix(a, "-") {
			filtered = append(filtered, a)
			continue
		}
		switch strings.ToLower(a) {
		case "mirror":
			cmd = "mirror"
		case "stop":
			cmd = "stop"
		case "status":
			cmd = "status"
		default:
			filtered = append(filtered, a)
		}
	}

	fs := flag.NewFlagSet("booxcpy", flag.ExitOnError)

	var (
		sizeStr  string
		apkPath  string
		serial   string
		output   string
		noPlay   bool
		silent   bool
		noMirror bool
		record   bool
		install  bool
		fps      int
		bitrate  int
		verbose  bool
		noLaunch bool
		preset   string
	)

	fs.StringVar(&serial, "serial", "", "adb device serial (if multiple devices connected)")
	fs.BoolVar(&install, "install", false, "force install APK even if already installed")
	fs.StringVar(&apkPath, "apk", defaultAPKPath(), "path to BooxStream APK")
	fs.StringVar(&sizeStr, "size", "1280x720", "capture size WxH (sent to app)")
	fs.IntVar(&fps, "fps", 12, "capture fps (sent to app)")
	fs.IntVar(&bitrate, "bitrate", 0, "bitrate in bps (0=auto in app)")
	fs.BoolVar(&noPlay, "no-play", false, "do not play stream (alias of --silent)")
	fs.BoolVar(&silent, "silent", false, "do not play stream (record-only if --record)")
	fs.BoolVar(&noMirror, "no-mirror", false, "do not play stream (record-only if --record)")
	fs.BoolVar(&record, "record", false, "record stream to file")
	fs.StringVar(&output, "output", "", "record output filename (default: booxstream_YYYYMMDD_HHMMSS.ivf)")
	fs.BoolVar(&verbose, "v", false, "verbose logging")
	fs.BoolVar(&noLaunch, "no-launch", false, "debug: don't start the app activity (assume already streaming)")
	fs.StringVar(&preset, "preset", "", "device preset: leaf3c, noteair, custom")

	_ = fs.Parse(filtered)

	w, h, err := parseSize(sizeStr)
	dieIf(err)

	w, h, fps, bitrate, err = applyPreset(preset, w, h, fps, bitrate)
	dieIf(err)

	if output != "" {
		record = true
	}
	noPlay = noPlay || silent || noMirror

	cfg := Config{
		Serial:   serial,
		Install:  install,
		APKPath:  apkPath,
		NoPlay:   noPlay,
		Record:   record,
		Output:   output,
		Width:    w,
		Height:   h,
		FPS:      fps,
		Bitrate:  bitrate,
		Verbose:  verbose,
		NoLaunch: noLaunch,
		Preset:   preset,
	}
	return cmd, cfg
}

type statsWriter struct {
	w          io.Writer
	bytesTotal atomic.Int64
	framesTotal atomic.Int64
}

func (s *statsWriter) Write(p []byte) (int, error) {
	n, err := s.w.Write(p)
	if n > 0 {
		s.bytesTotal.Add(int64(n))
	}
	return n, err
}

func (s *statsWriter) addFrame() {
	s.framesTotal.Add(1)
}

func (s *statsWriter) snapshot() (bytes int64, frames int64) {
	return s.bytesTotal.Load(), s.framesTotal.Load()
}

func startLiveCLIStats(ctx context.Context, sw *statsWriter, label string) func() {
	start := time.Now()
	done := make(chan struct{})

	go func() {
		ticker := time.NewTicker(1 * time.Second)
		defer ticker.Stop()

		var lastBytes int64
		var lastFrames int64

		for {
			select {
			case <-ctx.Done():
				return
			case <-done:
				return
			case <-ticker.C:
				totalBytes, totalFrames := sw.snapshot()

				deltaBytes := totalBytes - lastBytes
				deltaFrames := totalFrames - lastFrames
				lastBytes = totalBytes
				lastFrames = totalFrames

				kbps := float64(deltaBytes*8) / 1000.0
				elapsed := time.Since(start).Round(time.Second)
				totalMB := float64(totalBytes) / (1024.0 * 1024.0)

				fmt.Fprintf(
					os.Stderr,
					"[stats] %s elapsed=%s fps=%.2f kbps=%.0f frames=%d total=%.2fMB\n",
					label,
					elapsed,
					float64(deltaFrames),
					kbps,
					totalFrames,
					totalMB,
				)
			}
		}
	}()

	return func() {
		close(done)
	}
}

func applyPreset(preset string, w, h, fps, bitrate int) (int, int, int, int, error) {
	switch strings.ToLower(strings.TrimSpace(preset)) {
	case "", "custom":
		return w, h, fps, bitrate, nil
	case "leaf3c":
		return 960, 540, 8, 900000, nil
	case "noteair":
		return 1024, 600, 6, 1200000, nil
	default:
		return 0, 0, 0, 0, fmt.Errorf("unknown preset %q (supported: leaf3c, noteair, custom)", preset)
	}
}

func defaultAPKPath() string {
	return filepath.FromSlash("android/app/build/outputs/apk/debug/app-debug.apk")
}

func defaultRecordName() string {
	ts := time.Now().Format("20060102_150405")
	return fmt.Sprintf("booxstream_%s.ivf", ts)
}

func parseSize(s string) (int, int, error) {
	re := regexp.MustCompile(`^\s*(\d+)\s*[xX]\s*(\d+)\s*$`)
	m := re.FindStringSubmatch(s)
	if m == nil {
		return 0, 0, fmt.Errorf("invalid --size %q, expected WxH like 1280x720", s)
	}

	var w, h int
	if n, err := fmt.Sscanf(m[1], "%d", &w); err != nil || n != 1 {
		return 0, 0, fmt.Errorf("invalid width in --size %q", s)
	}
	if n, err := fmt.Sscanf(m[2], "%d", &h); err != nil || n != 1 {
		return 0, 0, fmt.Errorf("invalid height in --size %q", s)
	}

	if w < 320 || h < 320 {
		return 0, 0, fmt.Errorf("size too small: %dx%d", w, h)
	}
	return w, h, nil
}

func ensureDevice(ctx context.Context, adb *ADB) (string, error) {
	devs, err := adb.Devices(ctx)
	if err != nil {
		return "", err
	}
	if adb.Serial != "" {
		for _, d := range devs {
			if d == adb.Serial {
				return d, nil
			}
		}
		return "", fmt.Errorf("adb device %q not found (run: adb devices)", adb.Serial)
	}
	if len(devs) == 0 {
		return "", errors.New("no adb devices found (connect via USB, enable developer options + USB debugging)")
	}
	if len(devs) == 1 {
		return devs[0], nil
	}
	return "", fmt.Errorf("multiple adb devices found (%v). Use --serial <id>", devs)
}

func ensureInstalled(ctx context.Context, adb *ADB, cfg Config) error {
	installed, err := adb.IsPackageInstalled(ctx, pkgName)
	if err != nil {
		return err
	}
	if installed && !cfg.Install {
		return nil
	}

	if cfg.APKPath == "" {
		return errors.New("missing --apk path")
	}
	if _, err := os.Stat(cfg.APKPath); err != nil {
		return fmt.Errorf("APK not found at %q (build it or pass --apk): %w", cfg.APKPath, err)
	}

	fmt.Println("Installing APK:", cfg.APKPath)
	return adb.Install(ctx, cfg.APKPath)
}

func sendHostConfig(ctx context.Context, adb *ADB, cfg Config) error {
	args := []string{
		"shell", "am", "broadcast",
		"-n", "io.github.piyushdaiya.booxstream/.HostCommandReceiver",
		"-a", "io.github.piyushdaiya.booxstream.action.SET_CONFIG",
		"--ez", "io.github.piyushdaiya.booxstream.extra.AUTOSTART", "true",
		"--ei", "io.github.piyushdaiya.booxstream.extra.WIDTH", fmt.Sprintf("%d", cfg.Width),
		"--ei", "io.github.piyushdaiya.booxstream.extra.HEIGHT", fmt.Sprintf("%d", cfg.Height),
		"--ei", "io.github.piyushdaiya.booxstream.extra.FPS", fmt.Sprintf("%d", cfg.FPS),
		"--ei", "io.github.piyushdaiya.booxstream.extra.BITRATE", fmt.Sprintf("%d", cfg.Bitrate),
	}

	out, err := adb.Run(ctx, args...)
	if adb.Verbose && strings.TrimSpace(out) != "" {
		fmt.Fprintln(os.Stderr, "[am broadcast output]")
		fmt.Fprintln(os.Stderr, out)
	}
	if err != nil {
		return fmt.Errorf("failed to send host config: %w", err)
	}

	lower := strings.ToLower(out)
	if strings.Contains(lower, "exception") || strings.Contains(lower, "error") {
		return fmt.Errorf("host config broadcast failed: %s", strings.TrimSpace(out))
	}
	return nil
}

func startMirroringActivity(ctx context.Context, adb *ADB, cfg Config) error {
	out, err := adb.Run(ctx, "shell", "am", "start", "-W", "-n", mainActivity)
	if adb.Verbose && strings.TrimSpace(out) != "" {
		fmt.Fprintln(os.Stderr, "[am start output]")
		fmt.Fprintln(os.Stderr, out)
	}
	if err != nil {
		return fmt.Errorf("failed to launch BooxStream activity: %w", err)
	}

	lower := strings.ToLower(out)
	if strings.Contains(lower, "error type") ||
		strings.Contains(lower, "exception") ||
		strings.Contains(lower, "unable to resolve intent") ||
		(strings.Contains(lower, "activity class") && strings.Contains(lower, "does not exist")) {
		return fmt.Errorf("activity launch failed: %s", strings.TrimSpace(out))
	}

	if err := sendHostConfig(ctx, adb, cfg); err != nil {
		return err
	}

	fmt.Println("Launched BooxStream on device. If prompted, tap “Start now”.")
	return nil
}

func stopRemote(ctx context.Context, adb *ADB, cfg Config) error {
	_ = adb.ForwardRemove(ctx, adbForwardPort)
	_ = stopServiceQuiet(ctx, adb, cfg.Verbose)
	fmt.Println("Stopped (if it was running).")
	return nil
}

func statusRemote(ctx context.Context, adb *ADB, cfg Config) error {
	installed, _ := adb.IsPackageInstalled(ctx, pkgName)
	pid, _ := adb.PidOf(ctx, pkgName)
	running, details, _ := adb.IsServiceRunning(ctx, serviceComponent)

	fmt.Println("Device:", adb.Serial)
	fmt.Println("Package installed:", installed)
	if pid != "" {
		fmt.Println("App PID:", pid)
	} else {
		fmt.Println("App PID: (not running)")
	}
	fmt.Println("Service running:", running)
	if details != "" && cfg.Verbose {
		fmt.Println(details)
	}
	return nil
}

func stopServiceQuiet(ctx context.Context, adb *ADB, verbose bool) error {
	out, err := adb.Run(ctx, "shell", "am", "stopservice", "-n", serviceComponent)
	if err != nil {
		return err
	}

	trim := strings.TrimSpace(out)
	if trim == "" {
		return nil
	}

	if !verbose {
		if strings.Contains(trim, "was not running") ||
			strings.Contains(trim, "Service not stopped") ||
			strings.HasPrefix(trim, "Stopping service:") {
			return nil
		}
		return nil
	}

	fmt.Fprintln(os.Stderr, trim)
	return nil
}

func parseIvfHeader(hdr []byte) (w int, h int, fps int, ok bool) {
	if len(hdr) < 32 || !bytes.Equal(hdr[0:4], []byte("DKIF")) {
		return 0, 0, 0, false
	}
	w = int(binary.LittleEndian.Uint16(hdr[12:14]))
	h = int(binary.LittleEndian.Uint16(hdr[14:16]))
	timebaseDen := int(binary.LittleEndian.Uint32(hdr[16:20]))
	timebaseNum := int(binary.LittleEndian.Uint32(hdr[20:24]))
	if timebaseNum <= 0 || timebaseDen <= 0 {
		return w, h, 0, true
	}
	fps = timebaseDen / timebaseNum
	return w, h, fps, true
}

func isVP8Keyframe(frame []byte) bool {
	if len(frame) < 10 {
		return false
	}
	if (frame[0] & 0x01) != 0 {
		return false
	}
	return frame[3] == 0x9d && frame[4] == 0x01 && frame[5] == 0x2a
}

func waitForIvfStream(ctx context.Context, addr string, cfg Config) (net.Conn, []byte, error) {
	dialer := net.Dialer{Timeout: 500 * time.Millisecond}

	for {
		select {
		case <-ctx.Done():
			return nil, nil, ctx.Err()
		default:
		}

		c, err := dialer.DialContext(ctx, "tcp", addr)
		if err != nil {
			time.Sleep(200 * time.Millisecond)
			continue
		}

		_ = c.SetReadDeadline(time.Now().Add(1500 * time.Millisecond))

		streamHdr := make([]byte, 32)
		if _, err := io.ReadFull(c, streamHdr); err != nil {
			_ = c.Close()
			time.Sleep(200 * time.Millisecond)
			continue
		}

		w, h, fps, ok := parseIvfHeader(streamHdr)
		if !ok {
			_ = c.Close()
			time.Sleep(200 * time.Millisecond)
			continue
		}
		if w != cfg.Width || h != cfg.Height {
			_ = c.Close()
			time.Sleep(200 * time.Millisecond)
			continue
		}
		if fps > 0 && fps != cfg.FPS {
			_ = c.Close()
			time.Sleep(200 * time.Millisecond)
			continue
		}

		var prefix bytes.Buffer
		prefix.Write(streamHdr)

		foundKeyframe := false
		for i := 0; i < 60; i++ {
			frameHdr := make([]byte, 12)
			if _, err := io.ReadFull(c, frameHdr); err != nil {
				break
			}

			frameSize := binary.LittleEndian.Uint32(frameHdr[0:4])
			if frameSize == 0 || frameSize > 16*1024*1024 {
				break
			}

			frame := make([]byte, frameSize)
			if _, err := io.ReadFull(c, frame); err != nil {
				break
			}

			if isVP8Keyframe(frame) {
				prefix.Write(frameHdr)
				prefix.Write(frame)
				foundKeyframe = true
				break
			}
		}

		_ = c.SetReadDeadline(time.Time{})

		if !foundKeyframe {
			_ = c.Close()
			time.Sleep(200 * time.Millisecond)
			continue
		}

		fmt.Fprintf(os.Stderr, "Connected to stream: %dx%d @ %dfps (decoder-safe start)\n", w, h, fps)
		return c, prefix.Bytes(), nil
	}
}

func runFFplayFromStdin(ctx context.Context, title string, extraArgs []string) (*exec.Cmd, io.WriteCloser, error) {
	args := []string{
		"-window_title", title,
		"-fflags", "nobuffer",
		"-flags", "low_delay",
		"-framedrop",
		"-f", "ivf",
		"-i", "pipe:0",
	}
	args = append(args, extraArgs...)

	cmd := exec.CommandContext(ctx, "ffplay", args...)
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr

	in, err := cmd.StdinPipe()
	if err != nil {
		return nil, nil, err
	}
	if err := cmd.Start(); err != nil {
		_ = in.Close()
		return nil, nil, err
	}
	return cmd, in, nil
}

func streamToRecordOnly(ctx context.Context, conn net.Conn, prefix []byte, recordPath string) error {
	defer conn.Close()

	f, err := os.Create(recordPath)
	if err != nil {
		return err
	}
	defer f.Close()

	bw := bufio.NewWriterSize(f, 256*1024)
	defer bw.Flush()

	if _, err := bw.Write(prefix); err != nil {
		return err
	}

	sw := &statsWriter{w: bw}
	stopStats := startLiveCLIStats(ctx, sw, "record")
	defer stopStats()

	done := make(chan struct{})
	go func() {
		select {
		case <-ctx.Done():
			_ = conn.Close()
		case <-done:
		}
	}()

	frameBufHdr := make([]byte, 12)
	for {
		if _, err := io.ReadFull(conn, frameBufHdr); err != nil {
			close(done)
			if errors.Is(ctx.Err(), context.Canceled) {
				return ctx.Err()
			}
			if err == io.EOF {
				return nil
			}
			return err
		}

		frameSize := binary.LittleEndian.Uint32(frameBufHdr[0:4])
		if frameSize == 0 || frameSize > 16*1024*1024 {
			close(done)
			return fmt.Errorf("invalid IVF frame size: %d", frameSize)
		}

		frame := make([]byte, frameSize)
		if _, err := io.ReadFull(conn, frame); err != nil {
			close(done)
			if errors.Is(ctx.Err(), context.Canceled) {
				return ctx.Err()
			}
			return err
		}

		if _, err := sw.Write(frameBufHdr); err != nil {
			close(done)
			return err
		}
		if _, err := sw.Write(frame); err != nil {
			close(done)
			return err
		}
		sw.addFrame()
	}
}

func streamToPlayerAndOptionalRecord(ctx context.Context, serial string, cfg Config, conn net.Conn, prefix []byte, recordPath string) error {
	defer conn.Close()

	title := fmt.Sprintf(
		"BooxStream LIVE [%s] %dx%d %dfps",
		serial,
		cfg.Width,
		cfg.Height,
		cfg.FPS,
	)

	cmd, ffIn, err := runFFplayFromStdin(ctx, title, nil)
	if err != nil {
		return err
	}
	defer ffIn.Close()

	fmt.Println("Playing via ffplay...")

	var outW io.Writer = ffIn
	var f *os.File
	var bw *bufio.Writer

	if recordPath != "" {
		f, err = os.Create(recordPath)
		if err != nil {
			return err
		}
		defer f.Close()
		bw = bufio.NewWriterSize(f, 256*1024)
		defer bw.Flush()
		outW = io.MultiWriter(ffIn, bw)
	}

	if _, err := outW.Write(prefix); err != nil {
		return err
	}

	sw := &statsWriter{}
	sw.w = outW

	// Count the prefetched first keyframe packet as one frame.
	sw.bytesTotal.Add(int64(len(prefix)))
	sw.addFrame()

	stopStats := startLiveCLIStats(ctx, sw, "mirror")
	defer stopStats()

	done := make(chan struct{})
	go func() {
		select {
		case <-ctx.Done():
			_ = conn.Close()
			_ = ffIn.Close()
		case <-done:
		}
	}()

	frameBufHdr := make([]byte, 12)
	var copyErr error

	for {
		if _, err := io.ReadFull(conn, frameBufHdr); err != nil {
			if err == io.EOF {
				copyErr = nil
			} else {
				copyErr = err
			}
			break
		}

		frameSize := binary.LittleEndian.Uint32(frameBufHdr[0:4])
		if frameSize == 0 || frameSize > 16*1024*1024 {
			copyErr = fmt.Errorf("invalid IVF frame size: %d", frameSize)
			break
		}

		frame := make([]byte, frameSize)
		if _, err := io.ReadFull(conn, frame); err != nil {
			copyErr = err
			break
		}

		if _, err := sw.Write(frameBufHdr); err != nil {
			copyErr = err
			break
		}
		if _, err := sw.Write(frame); err != nil {
			copyErr = err
			break
		}
		sw.addFrame()
	}

	close(done)

	_ = ffIn.Close()

	waitDone := make(chan error, 1)
	go func() { waitDone <- cmd.Wait() }()

	select {
	case <-time.After(2 * time.Second):
		_ = cmd.Process.Kill()
	case <-waitDone:
	}

	if errors.Is(ctx.Err(), context.Canceled) {
		return ctx.Err()
	}

	if copyErr != nil {
		if copyErr == io.EOF {
			return fmt.Errorf("stream ended unexpectedly")
		}
		if strings.Contains(strings.ToLower(copyErr.Error()), "broken pipe") {
			return nil
		}
		return copyErr
	}

	return nil
}

func dieIf(err error) {
	if err == nil {
		return
	}
	fmt.Fprintln(os.Stderr, "Error:", err)
	if runtime.GOOS == "windows" {
		fmt.Fprintln(os.Stderr, "Tip (Windows): ensure adb.exe and ffplay.exe are in PATH.")
	}
	os.Exit(1)
}

type ADB struct {
	Serial  string
	Verbose bool
}

func (a *ADB) baseArgs() []string {
	if a.Serial == "" {
		return []string{}
	}
	return []string{"-s", a.Serial}
}

func (a *ADB) Run(ctx context.Context, args ...string) (string, error) {
	full := append(a.baseArgs(), args...)
	if a.Verbose {
		fmt.Fprintln(os.Stderr, "adb", strings.Join(full, " "))
	}
	cmd := exec.CommandContext(ctx, "adb", full...)
	var stdout, stderr bytes.Buffer
	cmd.Stdout = &stdout
	cmd.Stderr = &stderr
	err := cmd.Run()
	outStr := strings.TrimSpace(stdout.String())
	errStr := strings.TrimSpace(stderr.String())
	if err != nil {
		if errStr != "" {
			return outStr, fmt.Errorf("%w: %s", err, errStr)
		}
		return outStr, err
	}
	if a.Verbose && errStr != "" {
		fmt.Fprintln(os.Stderr, errStr)
	}
	return outStr, nil
}

func (a *ADB) Devices(ctx context.Context) ([]string, error) {
	out, err := a.Run(ctx, "devices")
	if err != nil {
		return nil, err
	}
	var devs []string
	sc := bufio.NewScanner(strings.NewReader(out))
	for sc.Scan() {
		line := strings.TrimSpace(sc.Text())
		if line == "" || strings.HasPrefix(line, "List of devices") {
			continue
		}
		fields := strings.Fields(line)
		if len(fields) >= 2 && fields[1] == "device" {
			devs = append(devs, fields[0])
		}
	}
	return devs, nil
}

func (a *ADB) IsPackageInstalled(ctx context.Context, pkg string) (bool, error) {
	out, err := a.Run(ctx, "shell", "pm", "path", pkg)
	if err != nil {
		return false, nil
	}
	return strings.Contains(out, "package:"), nil
}

func (a *ADB) Install(ctx context.Context, apkPath string) error {
	_, err := a.Run(ctx, "install", "-r", "-g", apkPath)
	return err
}

func (a *ADB) Forward(ctx context.Context, port int, target string) error {
	_, err := a.Run(ctx, "forward", fmt.Sprintf("tcp:%d", port), target)
	return err
}

func (a *ADB) ForwardRemove(ctx context.Context, port int) error {
	_, err := a.Run(ctx, "forward", "--remove", fmt.Sprintf("tcp:%d", port))
	return err
}

func (a *ADB) PidOf(ctx context.Context, pkg string) (string, error) {
	out, err := a.Run(ctx, "shell", "pidof", pkg)
	if err != nil {
		return "", nil
	}
	return strings.TrimSpace(out), nil
}

func (a *ADB) IsServiceRunning(ctx context.Context, component string) (bool, string, error) {
	out, err := a.Run(ctx, "shell", "dumpsys", "activity", "services", pkgName)
	if err != nil {
		out2, err2 := a.Run(ctx, "shell", "dumpsys", "activity", "services")
		if err2 != nil {
			return false, "", nil
		}
		out = out2
	}

	if strings.Contains(out, component) {
		lines := strings.Split(out, "\n")
		var snippet []string
		for _, ln := range lines {
			if strings.Contains(ln, component) {
				snippet = append(snippet, strings.TrimRight(ln, "\r"))
				if len(snippet) >= 6 {
					break
				}
			}
		}
		return true, strings.Join(snippet, "\n"), nil
	}
	return false, "", nil
}