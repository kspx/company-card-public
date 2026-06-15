import { HttpInterceptorFn } from '@angular/common/http';

/**
 * Adds withCredentials: true to every request so the JSESSIONID session cookie
 * is sent automatically after login. No manual credential headers needed.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
    return next(req.clone({ withCredentials: true }));
};
