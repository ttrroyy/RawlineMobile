package providergeo

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/netip"
	"os"
	"path/filepath"
	"regexp"
	"strings"

	"github.com/sagernet/sing-box/common/srs"
	"github.com/sagernet/sing-box/option"
	"github.com/xtls/xray-core/common/geodata"
	"google.golang.org/protobuf/proto"
)

// ValidateXray decodes one category at a time using the same schema as the reader.
func ValidateXray(path string, geosite bool) error {
	return entries(path, func(_ string, entry *io.SectionReader) (bool, error) {
		reader := newGeoReader(entry)
		for {
			tag, record, err := field(reader)
			if err == io.EOF {
				return false, nil
			}
			if err != nil {
				return false, err
			}
			if tag != 18 {
				continue
			}
			if err := validateXrayRecord(record, geosite); err != nil {
				return false, err
			}
		}
	})
}

func validateXrayRecord(record *io.SectionReader, geosite bool) error {
	if record.Size() > 1<<20 {
		return fmt.Errorf("Xray geo record exceeds 1 MiB")
	}
	data, err := io.ReadAll(record)
	if err != nil {
		return err
	}
	if geosite {
		var domain geodata.Domain
		if err := proto.Unmarshal(data, &domain); err != nil {
			return err
		}
		switch domain.Type {
		case geodata.Domain_Substr, geodata.Domain_Domain, geodata.Domain_Full:
			return nil
		case geodata.Domain_Regex:
			_, err := regexp.Compile(domain.Value)
			return err
		default:
			return fmt.Errorf("unsupported geosite domain type")
		}
	}
	var cidr geodata.CIDR
	if err := proto.Unmarshal(data, &cidr); err != nil {
		return err
	}
	address, ok := netip.AddrFromSlice(cidr.Ip)
	if !ok || cidr.Prefix > uint32(address.BitLen()) {
		return fmt.Errorf("invalid geoip CIDR")
	}
	return nil
}

// ConvertGeoRuleSet keeps the provider's own geo categories rather than substituting another database.
func Convert(input, output, kind, selector string) error {
	if kind != "geoip" && kind != "geosite" {
		return fmt.Errorf("unsupported geo kind")
	}
	if selector == "" {
		return fmt.Errorf("empty geo category")
	}
	parts := strings.SplitN(selector, "@", 2)
	var entry []byte
	err := entries(input, func(code string, r *io.SectionReader) (bool, error) {
		if !strings.EqualFold(code, parts[0]) {
			return false, nil
		}
		var err error
		entry, err = io.ReadAll(r)
		return true, err
	})
	if err != nil {
		return err
	}
	if len(entry) == 0 {
		return fmt.Errorf("geo category %s not found", selector)
	}
	fields := map[string][]string{}
	if kind == "geosite" {
		var site geodata.GeoSite
		if err = proto.Unmarshal(entry, &site); err != nil {
			return err
		}
		var matcher geodata.AttributeMatcher
		if len(parts) == 2 {
			matcher = geodata.NewAllAttrsMatcher(parts[1])
		}
		for _, d := range site.Domain {
			if matcher != nil && !matcher.Match(d) {
				continue
			}
			key := ""
			switch d.Type {
			case geodata.Domain_Substr:
				key = "domain_keyword"
			case geodata.Domain_Regex:
				key = "domain_regex"
			case geodata.Domain_Domain:
				key = "domain_suffix"
			case geodata.Domain_Full:
				key = "domain"
			default:
				return fmt.Errorf("unsupported geo domain type")
			}
			fields[key] = append(fields[key], d.Value)
		}
	} else if kind == "geoip" {
		var ips geodata.GeoIP
		if err = proto.Unmarshal(entry, &ips); err != nil {
			return err
		}
		if ips.ReverseMatch {
			return fmt.Errorf("reverse geo category is not supported")
		}
		for _, c := range ips.Cidr {
			address, ok := netip.AddrFromSlice(c.Ip)
			if !ok || int(c.Prefix) > address.BitLen() {
				return fmt.Errorf("invalid geo CIDR")
			}
			fields["ip_cidr"] = append(fields["ip_cidr"], netip.PrefixFrom(address, int(c.Prefix)).Masked().String())
		}
	} else {
		return fmt.Errorf("unsupported geo kind")
	}
	if len(fields) == 0 {
		return fmt.Errorf("geo category %s is empty", selector)
	}
	b, err := json.Marshal(map[string]any{"rules": []any{fields}})
	if err != nil {
		return err
	}
	var rules option.PlainRuleSet
	if err = json.Unmarshal(b, &rules); err != nil {
		return err
	}
	var encoded bytes.Buffer
	if err = srs.Write(&encoded, rules, 3); err != nil {
		return err
	}
	temp, err := os.CreateTemp(filepath.Dir(output), ".geo-*.srs")
	if err != nil {
		return err
	}
	defer os.Remove(temp.Name())
	if _, err = temp.Write(encoded.Bytes()); err != nil {
		temp.Close()
		return err
	}
	if err = temp.Close(); err != nil {
		return err
	}
	return os.Rename(temp.Name(), output)
}
