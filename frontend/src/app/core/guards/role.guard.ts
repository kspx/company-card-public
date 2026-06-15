import { Injectable } from '@angular/core';
import { CanActivate, Router } from '@angular/router';
import { AuthService } from '@app/core/services/auth.service';

@Injectable({ providedIn: 'root' })
export class RoleGuard implements CanActivate {
    constructor(private auth: AuthService, private router: Router) {}

    canActivate(): boolean {
        if (!this.auth.isAuthenticated) {
            void this.router.navigate(['/login']);
            return false;
        }
        if (this.auth.role !== 'ADMIN') {
            void this.router.navigate(['/company-card']);
            return false;
        }
        return true;
    }
}
