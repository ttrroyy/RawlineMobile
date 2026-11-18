package mobile

import "RawlineCore/internal/providergeo"

func ConvertGeoRuleSet(input, output, kind, selector string) error {
	return providergeo.Convert(input, output, kind, selector)
}

func ValidateGeoFile(path string) error {
	return providergeo.Validate(path)
}

func ValidateXrayGeoFile(path string, geosite bool) error {
	return providergeo.ValidateXray(path, geosite)
}

// NormalizeGeoFileForXray makes a provider geo file's categories findable by Xray.
func NormalizeGeoFileForXray(path string) error {
	return providergeo.NormalizeForXray(path)
}
