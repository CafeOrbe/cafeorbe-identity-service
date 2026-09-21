package com.cafeorbe.identity.controller;

import java.util.Map;

/** Formato uniforme de error: mensaje legible y, si aplica, el detalle por campo. */
public record ApiError(int status, String mensaje, Map<String, String> campos) {
}
