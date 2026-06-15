import { Component, ChangeDetectionStrategy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { AuthService } from '@app/core/services/auth.service';

@Component({
    selector: 'app-login',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [CommonModule, FormsModule],
    templateUrl: './login.component.html',
    styleUrls: ['./login.component.scss'],
})
export class LoginComponent {
    mode: 'login' | 'register' = 'login';
    email = '';
    password = '';
    confirmPassword = '';
    error: string | null = null;
    success: string | null = null;
    userExists = false;

    constructor(private auth: AuthService, private router: Router) {}

    switchMode(mode: 'login' | 'register') {
        this.mode = mode;
        this.error = null;
        this.success = null;
        this.userExists = false;
        this.password = '';
        this.confirmPassword = '';
    }

    onSubmit() {
        this.error = null;
        this.success = null;
        this.userExists = false;

        if (this.mode === 'register') {
            if (this.password !== this.confirmPassword) {
                this.error = 'Passwords do not match';
                return;
            }
            this.auth.register(this.email, this.password).subscribe({
                next: () => {
                    this.success = 'Account created! You can now log in.';
                    this.switchMode('login');
                },
                error: (err) => {
                    const msg: string = err?.error?.error || '';
                    if (msg.toLowerCase().includes('already exists')) {
                        this.userExists = true;
                    } else {
                        this.error = msg || 'Registration failed';
                    }
                },
            });
        } else {
            this.auth.login(this.email, this.password).subscribe({
                next: () => {
                    if (this.auth.role === 'ADMIN') {
                        void this.router.navigate(['/company-card']);
                    } else if (this.auth.personUri) {
                        const classId = encodeURIComponent('https://ontology.cogni.zone/company-card#Person');
                        const instanceId = encodeURIComponent(this.auth.personUri);
                        void this.router.navigate(['/company-card', classId, instanceId]);
                    } else {
                        void this.router.navigate(['/company-card']);
                    }
                },
                error: () => (this.error = 'Invalid credentials'),
            });
        }
    }
}
