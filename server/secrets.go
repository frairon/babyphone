package server

import (
	"crypto/sha256"
	"fmt"
)

func hashPassword(clear string) string {
	sum := sha256.Sum256([]byte(clear))
	return fmt.Sprintf("%x", sum)
}
