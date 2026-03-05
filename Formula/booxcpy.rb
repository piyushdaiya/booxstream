class Booxcpy < Formula
  desc "Screen mirroring for Boox e-ink devices"
  homepage "https://github.com/piyushdaiya/booxstream"
  url "https://github.com/piyushdaiya/booxstream/releases/download/v0.1.0/booxcpy-darwin-amd64.tar.gz"
  version "0.1.0"
  sha256 "REPLACE_WITH_SHA"

  def install
    bin.install "booxcpy-darwin-amd64" => "booxcpy"
  end
end