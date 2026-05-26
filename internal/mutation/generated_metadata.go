package mutation

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
)

const GeneratedMetadataDir = "metadata"

type GeneratedMetadata struct {
	SchemaVersion      int      `json:"schema_version"`
	FeaturePath        string   `json:"feature_path"`
	IRPath             string   `json:"ir_path"`
	ImplementationHash string   `json:"implementation_hash"`
	HashScope          string   `json:"hash_scope"`
	GeneratedFiles     []string `json:"generated_files"`
}

func ReadGeneratedMetadata(generatedDir string, featurePath string) (GeneratedMetadata, error) {
	file, err := os.Open(GeneratedMetadataPath(generatedDir, featurePath))
	if err != nil {
		return GeneratedMetadata{}, err
	}
	defer file.Close()

	var metadata GeneratedMetadata
	if err := json.NewDecoder(file).Decode(&metadata); err != nil {
		return GeneratedMetadata{}, err
	}
	if metadata.FeaturePath != featurePath {
		return GeneratedMetadata{}, os.ErrInvalid
	}
	return metadata, nil
}

func ResolveImplementationHash(generatedDir string, featurePath string, override string) string {
	if override != "" {
		return override
	}
	metadata, err := ReadGeneratedMetadata(generatedDir, featurePath)
	if err != nil || metadata.ImplementationHash == "" {
		return "unknown"
	}
	return metadata.ImplementationHash
}

func GeneratedMetadataPath(generatedDir string, featurePath string) string {
	return filepath.Join(generatedDir, GeneratedMetadataDir, FeatureMetadataSlug(featurePath)+".json")
}

func FeatureMetadataSlug(featurePath string) string {
	var builder strings.Builder
	previousHyphen := false
	for _, r := range strings.ToLower(featurePath) {
		if (r >= 'a' && r <= 'z') || (r >= '0' && r <= '9') {
			builder.WriteRune(r)
			previousHyphen = false
			continue
		}
		if !previousHyphen && builder.Len() > 0 {
			builder.WriteByte('-')
			previousHyphen = true
		}
	}
	return strings.Trim(builder.String(), "-")
}
