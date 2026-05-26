package mutation

import (
	"os"
	"path/filepath"
	"testing"
)

func TestResolveImplementationHashReadsGeneratedMetadata(t *testing.T) {
	dir := t.TempDir()
	path := GeneratedMetadataPath(dir, "features/Hunt The Wumpus.feature")
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte(`{
  "schema_version": 1,
  "feature_path": "features/Hunt The Wumpus.feature",
  "ir_path": "build/acceptance/hunt-the-wumpus.json",
  "implementation_hash": "sha256:generated",
  "hash_scope": "generated_files",
  "generated_files": ["acceptance/generated/test.go"]
}`), 0o644); err != nil {
		t.Fatal(err)
	}

	if got := ResolveImplementationHash(dir, "features/Hunt The Wumpus.feature", ""); got != "sha256:generated" {
		t.Fatalf("hash = %q", got)
	}
}

func TestResolveImplementationHashUsesOverride(t *testing.T) {
	dir := t.TempDir()
	path := GeneratedMetadataPath(dir, "features/a.feature")
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte(`{
  "feature_path": "features/a.feature",
  "implementation_hash": "sha256:generated"
}`), 0o644); err != nil {
		t.Fatal(err)
	}

	if got := ResolveImplementationHash(dir, "features/a.feature", "sha256:override"); got != "sha256:override" {
		t.Fatalf("hash = %q", got)
	}
}

func TestResolveImplementationHashFallsBackToUnknown(t *testing.T) {
	if got := ResolveImplementationHash(t.TempDir(), "features/missing.feature", ""); got != "unknown" {
		t.Fatalf("hash = %q", got)
	}
}

func TestResolveImplementationHashRejectsWrongFeaturePath(t *testing.T) {
	dir := t.TempDir()
	path := GeneratedMetadataPath(dir, "features/a.feature")
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte(`{
  "feature_path": "features/other.feature",
  "implementation_hash": "sha256:generated"
}`), 0o644); err != nil {
		t.Fatal(err)
	}

	if got := ResolveImplementationHash(dir, "features/a.feature", ""); got != "unknown" {
		t.Fatalf("hash = %q", got)
	}
}

func TestFeatureMetadataSlug(t *testing.T) {
	cases := map[string]string{
		"features/Hunt The Wumpus.feature":     "features-hunt-the-wumpus-feature",
		"features/orders/Cancel Order.feature": "features-orders-cancel-order-feature",
		"Features/API v2/Happy Path.feature":   "features-api-v2-happy-path-feature",
		"  Features//Odd\tName!!.feature  ":    "features-odd-name-feature",
	}
	for input, want := range cases {
		if got := FeatureMetadataSlug(input); got != want {
			t.Fatalf("FeatureMetadataSlug(%q) = %q, want %q", input, got, want)
		}
	}
}
