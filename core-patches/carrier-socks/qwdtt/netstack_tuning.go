package main

import (
	"reflect"
	"unsafe"

	"golang.zx2c4.com/wireguard/tun/netstack"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
)

// tuneNetstack keeps adaptive receive windows and larger TCP buffers for the relay path.
// Keep the stack's default congestion control: forcing CUBIC stalls sustained uploads
// in the gVisor version used by this netstack.
// netstack keeps its stack unexported; a layout change only skips the tuning.
func tuneNetstack(network *netstack.Net) {
	field := reflect.ValueOf(network).Elem().FieldByName("stack")
	if !field.IsValid() || field.Kind() != reflect.Pointer || field.Type() != reflect.TypeOf((*stack.Stack)(nil)) {
		return
	}
	s := (*stack.Stack)(unsafe.Pointer(field.Pointer()))
	moderate := tcpip.TCPModerateReceiveBufferOption(true)
	receive := tcpip.TCPReceiveBufferSizeRangeOption{Min: 4 << 10, Default: 1 << 20, Max: 8 << 20}
	send := tcpip.TCPSendBufferSizeRangeOption{Min: 4 << 10, Default: 1 << 20, Max: 8 << 20}
	for _, option := range []tcpip.SettableTransportProtocolOption{&moderate, &receive, &send} {
		_ = s.SetTransportProtocolOption(tcp.ProtocolNumber, option)
	}
}
