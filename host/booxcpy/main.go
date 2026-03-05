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
	NoPlay   bool // playback disabled (silent/no-mirror)
	Record   bool
	Output   string
	Width    int
	Height   int
	FPS      int
	Bitrate  int
	Verbose  bool
	NoLaunch bool
}

func main() {
	cmdName, cfg := parseArgs(os.Args[1:]) // robust parsing (flags before/after command)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	adb := &ADB{Serial: cfg.Serial, Verbose: cfg.Verbose}

	serial, err := ensureDevice(ctx, adb)
	dieIf(err)
	if cfg.Serial == "" {
		adb.Serial = serial
	}

	// ---- Subcommands that don't need streaming ----
	switch cmdName {
	case "stop":
		dieIf(stopRemote(ctx, adb, cfg))
		return
	case "status":
		dieIf(statusRemote(ctx, adb, cfg))
		return
	case "mirror":
		// continue
	default:
		dieIf(fmt.Errorf("unknown command %q (supported: mirror, stop, status)", cmdName))
	}

	// Ctrl+C handling + cleanup
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

	// Clean up old forward, then add forward.
	_ = adb.ForwardRemove(ctx, adbForwardPort)
	dieIf(adb.Forward(ctx, adbForwardPort, adbForwardTarget))
	defer cleanup("defer")

	if !cfg.NoLaunch {
		dieIf(startMirroringActivity(ctx, adb, cfg))
		fmt.Println("Waiting for stream... (accept the screen-capture prompt on the device)")
	} else {
		fmt.Println("Skipping activity launch (--no-launch). Assuming app is already streaming.")
	}

	// Recording path logic
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

	// Wait for a valid IVF header before starting playback/recording.
	addr := fmt.Sprintf("127.0.0.1:%d", adbForwardPort)
	waitCtx, waitCancel := context.WithTimeout(ctx, 60*time.Second)
	defer waitCancel()

	conn, first32, err := waitForIvfStream(waitCtx, addr)
	dieIf(err)

	// record-only
	if cfg.NoPlay {
		dieIf(streamToRecordOnly(ctx, conn, first32, recordPath))
		cleanup("record-only done")
		fmt.Println("Done.")
		return
	}

	// play (and optionally record)
	dieIf(streamToPlayerAndOptionalRecord(ctx, adb.Serial, conn, first32, recordPath))
	cleanup("mirror done")
	fmt.Println("Done.")
}

// ------------------ Args parsing ------------------

func parseArgs(argv []string) (string, Config) {
	// Command detection:
	// - default is "mirror"
	// - allow command token anywhere (flags before/after)
	// - strip the command token so flagset doesn't choke on it
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
			// drop token
		case "stop":
			cmd = "stop"
			// drop token
		case "status":
			cmd = "status"
			// drop token
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
	)

	fs.StringVar(&serial, "serial", "", "adb device serial (if multiple devices connected)")
	fs.BoolVar(&install, "install", false, "force install APK even if already installed")
	fs.StringVar(&apkPath, "apk", defaultAPKPath(), "path to BooxStream APK")
	fs.StringVar(&sizeStr, "size", "1280x720", "capture size WxH (sent to app)")
	fs.IntVar(&fps, "fps", 12, "capture fps (sent to app)")
	fs.IntVar(&bitrate, "bitrate", 0, "bitrate in bps (0=auto in app)")

	// playback flags (mirror is ON by default)
	fs.BoolVar(&noPlay, "no-play", false, "do not play stream (alias of --silent)")
	fs.BoolVar(&silent, "silent", false, "do not play stream (record-only if --record)")
	fs.BoolVar(&noMirror, "no-mirror", false, "do not play stream (record-only if --record)")

	// recording
	fs.BoolVar(&record, "record", false, "record stream to file")
	fs.StringVar(&output, "output", "", "record output filename (default: booxstream_YYYYMMDD_HHMMSS.ivf)")

	fs.BoolVar(&verbose, "v", false, "verbose logging")
	fs.BoolVar(&noLaunch, "no-launch", false, "debug: don't start the app activity (assume already streaming)")

	_ = fs.Parse(filtered)

	w, h, err := parseSize(sizeStr)
	dieIf(err)

	// If user provided --output, treat as --record (scrcpy-like ergonomics).
	if output != "" {
		record = true
	}

	// mirror on by default; only off if silent/no-mirror/no-play
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
	}
	return cmd, cfg
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
	fmt.Sscanf(m[1], "%d", &w)
	fmt.Sscanf(m[2], "%d", &h)
	if w < 320 || h < 320 {
		return 0, 0, fmt.Errorf("size too small: %dx%d", w, h)
	}
	return w, h, nil
}

// ------------------ Device / install / launch ------------------

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

func startMirroringActivity(ctx context.Context, adb *ADB, cfg Config) error {
	// Use -S to stop the app before starting (reliable fresh start)
	args := []string{
		"shell", "am", "start", "-S",
		"-n", mainActivity,
		"--ez", "booxstream_autostart", "true",
		"--ei", "width", fmt.Sprintf("%d", cfg.Width),
		"--ei", "height", fmt.Sprintf("%d", cfg.Height),
		"--ei", "fps", fmt.Sprintf("%d", cfg.FPS),
		"--ei", "bitrate", fmt.Sprintf("%d", cfg.Bitrate),
	}

	out, err := adb.Run(ctx, args...)
	if err != nil {
		return err
	}
	if strings.Contains(out, "Error") || strings.Contains(out, "Exception") {
		fmt.Fprintln(os.Stderr, out)
	}
	fmt.Println("Launched BooxStream on device. If prompted, tap “Start now”.")
	return nil
}

// ------------------ Commands: stop/status ------------------

func stopRemote(ctx context.Context, adb *ADB, cfg Config) error {
	_ = adb.ForwardRemove(ctx, adbForwardPort)
	if err := stopServiceQuiet(ctx, adb, cfg.Verbose); err != nil {
		return err
	}
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

	// Suppress idempotent stop noise unless verbose
	if !verbose {
		if strings.Contains(trim, "was not running") ||
			strings.Contains(trim, "Service not stopped") ||
			strings.HasPrefix(trim, "Stopping service:") {
			return nil
		}
		return nil
	}

	// Verbose: print what Android said
	fmt.Fprintln(os.Stderr, trim)
	return nil
}

// ------------------ Streaming (race-free) ------------------

func waitForIvfStream(ctx context.Context, addr string) (net.Conn, []byte, error) {
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

		_ = c.SetReadDeadline(time.Now().Add(800 * time.Millisecond))
		hdr := make([]byte, 32)
		_, err = io.ReadFull(c, hdr)
		_ = c.SetReadDeadline(time.Time{})

		if err != nil {
			_ = c.Close()
			time.Sleep(200 * time.Millisecond)
			continue
		}
		if !bytes.Equal(hdr[0:4], []byte("DKIF")) {
			_ = c.Close()
			time.Sleep(200 * time.Millisecond)
			continue
		}

		return c, hdr, nil
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

func streamToRecordOnly(ctx context.Context, conn net.Conn, first32 []byte, recordPath string) error {
	defer conn.Close()

	f, err := os.Create(recordPath)
	if err != nil {
		return err
	}
	defer f.Close()

	bw := bufio.NewWriterSize(f, 256*1024)
	defer bw.Flush()

	if _, err := bw.Write(first32); err != nil {
		return err
	}

	done := make(chan struct{})
	go func() {
		select {
		case <-ctx.Done():
			_ = conn.Close()
		case <-done:
		}
	}()

	_, err = io.CopyBuffer(bw, conn, make([]byte, 256*1024))
	close(done)

	if errors.Is(ctx.Err(), context.Canceled) {
		return ctx.Err()
	}
	return err
}

func streamToPlayerAndOptionalRecord(ctx context.Context, serial string, conn net.Conn, first32 []byte, recordPath string) error {
	defer conn.Close()

	title := fmt.Sprintf("BooxStream (%s)", serial)
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

	if _, err := outW.Write(first32); err != nil {
		return err
	}

	done := make(chan struct{})
	go func() {
		select {
		case <-ctx.Done():
			_ = conn.Close()
			_ = ffIn.Close()
		case <-done:
		}
	}()

	_, copyErr := io.CopyBuffer(outW, conn, make([]byte, 256*1024))
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

	// If ffplay exits early, we may see broken pipe; treat as normal stop.
	if copyErr != nil {
		if strings.Contains(strings.ToLower(copyErr.Error()), "broken pipe") {
			return nil
		}
	}
	return copyErr
}

// ------------------ misc ------------------

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

// ------------------ ADB helper ------------------

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